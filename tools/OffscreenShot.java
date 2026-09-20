import com.calendar.data.AppPaths;
import com.calendar.data.ScheduleStore;
import com.calendar.model.Repeat;
import com.calendar.model.Schedule;
import com.calendar.ui.CalendarWindow;
import com.calendar.ui.Theme;

import javax.imageio.ImageIO;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/**
 * 离屏渲染截图——**完全不接触屏幕**。
 *
 * <h2>为什么要有这个工具</h2>
 * 之前的 {@code OpacityShot} / {@code ScreenShot} / {@code MarketShot} 走的是
 * {@code Robot.createScreenCapture}，抓的是"合成之后的屏幕像素"。而挂件默认 92% 不透明，
 * 于是**桌面内容会连同挂件一起被拍进图片**——包括用户当时打开的聊天窗口。
 * 这类截图一旦外发就是隐私事故，而且肉眼看小图不容易发现。
 *
 * <h2>怎么做到既不拍屏幕、又能体现透明度</h2>
 * 分两步：
 * <ol>
 *   <li>{@code contentPane.paint()} 把真实组件树画进一张 ARGB 内存图（这一步和
 *       {@code RenderPreview} 一样，永远不碰屏幕）；</li>
 *   <li>按指定不透明度把这层内容 <b>合成到一张程序生成的背景</b>上——
 *       背景是画出来的色块，不是真实桌面。</li>
 * </ol>
 * 因为窗口级不透明度在视觉上就是"整窗按 alpha 叠加到底层"，第 2 步的合成结果
 * 与真实上屏效果一致，但背景完全可控。
 *
 * <h2>用法</h2>
 * <pre>
 *   # 取依赖 classpath（Windows 用 ; 分隔，Linux/macOS 用 :）
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *
 *   javac -encoding UTF-8 -cp "target/classes;$(cat target/cp.txt)" \
 *         -d target/tools tools/OffscreenShot.java
 *
 *   java -cp "target/classes;$(cat target/cp.txt);target/tools" \
 *        OffscreenShot &lt;模式&gt; &lt;不透明度&gt; &lt;输出.png&gt; [宽x高] [menu]
 * </pre>
 * <ul>
 *   <li>模式：{@code today}（今日）或 {@code month}（月历）</li>
 *   <li>不透明度：50~100 的整数</li>
 *   <li>宽x高：可选，默认 {@code 390x590}</li>
 *   <li>{@code menu}：可选，额外把右键菜单画进去</li>
 * </ul>
 *
 * <p>输出路径建议放在 `out/preview/` 下（`.gitignore` 已忽略 `/out/`）。
 *
 * <p>背景色可用 {@code -Doffscreen.backdrop=RRGGBB} 覆盖。
 */
public class OffscreenShot {

    private static final int DEFAULT_WIDTH = 390;
    private static final int DEFAULT_HEIGHT = 590;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: OffscreenShot <today|month> <不透明度> <输出.png> [宽x高] [menu]");
            return;
        }
        String mode = args[0];
        int opacity = Integer.parseInt(args[1]);
        File target = new File(args[2]);
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;
        if (args.length > 3 && args[3].contains("x")) {
            String[] parts = args[3].split("x");
            width = Integer.parseInt(parts[0]);
            height = Integer.parseInt(parts[1]);
        }
        boolean withMenu = false;
        for (String a : args) {
            if ("menu".equals(a)) withMenu = true;
        }

        // 示例日程不能写进用户真实数据库，视图偏好也不能覆盖用户的。
        System.setProperty("desktopcalendar.dataDir",
                Files.createTempDirectory("desktopcalendar-offscreen").toString());
        System.setProperty("desktopcalendar.prefsPath", "/DesktopCalendarOffscreen");
        Preferences preferences = Preferences.userRoot().node(AppPaths.PREFERENCES_PATH);
        preferences.clear();
        preferences.flush();

        LocalDate today = LocalDate.now();
        ScheduleStore store = new ScheduleStore(Throwable::printStackTrace);
        store.createSchema();
        store.insert(new Schedule(0, today, "季度评审会", "3楼会议室 A",
                LocalTime.of(10, 0), LocalTime.of(11, 30), Repeat.NONE, null, 15, "", false));
        store.insert(new Schedule(0, today, "与设计对齐挂件改版", "线上",
                LocalTime.of(14, 0), LocalTime.of(15, 0), Repeat.WEEKLY, null, 15, "", false));
        store.insert(new Schedule(0, today, "整理下周排期", "",
                LocalTime.of(17, 30), LocalTime.of(18, 0), Repeat.NONE, null, -1, "", false));

        final int fw = width;
        final int fh = height;
        AtomicReference<CalendarWindow> holder = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            CalendarWindow window = new CalendarWindow();
            // 关键：只做布局，不上屏。addNotify 让组件拿到对等体，布局与字体度量才正确。
            window.addNotify();
            window.setSize(fw, fh);
            if ("month".equals(mode)) {
                window.setMonthMode(true);
            }
            window.validate();
            holder.set(window);
        });

        BufferedImage result = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 第 1 步：画背景（程序生成的桌面替身，不含任何真实内容）
        paintSyntheticDesktop(graphics, fw, fh);

        // 第 2 步：把真实组件树画到独立的透明层
        BufferedImage layer = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D layerGraphics = layer.createGraphics();
        layerGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        layerGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        holder.get().getContentPane().paint(layerGraphics);

        // 第 3 步：按不透明度把内容层合成到背景上——这就是窗口级透明度的视觉效果
        graphics.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, opacity / 100f))));
        graphics.drawImage(layer, 0, 0, null);
        graphics.setComposite(AlphaComposite.SrcOver);

        // 第 4 步：可选——把右键菜单也离屏画上去。
        // 菜单是独立的重型窗口，不受窗口不透明度影响，所以按 100% 画。
        if (withMenu) {
            BufferedImage menuLayer = renderMenu(holder.get(), fw, fh);
            if (menuLayer != null) {
                graphics.drawImage(menuLayer, 0, 0, null);
            }
        }
        graphics.dispose();

        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(result, "png", target);
        System.out.println("已输出 " + target.getAbsolutePath() + "  (" + fw + "x" + fh
                + ", 模式=" + mode + ", 不透明度=" + opacity + "%, 菜单=" + withMenu + ")");
        System.out.println("本图完全离屏生成，不含任何屏幕/桌面像素。");

        SwingUtilities.invokeAndWait(() -> {
            holder.get().dispose();
            holder.get().shutdown();
        });
        System.exit(0);
    }

    /**
     * 程序生成的"桌面替身"：柔和底色 + 几块圆角色块。
     * 目的只是让半透明效果看得出来，内容纯属虚构。
     */
    private static void paintSyntheticDesktop(Graphics2D graphics, int width, int height) {
        String override = System.getProperty("offscreen.backdrop");
        Color base = override != null ? Color.decode("#" + override) : new Color(0xE9, 0xED, 0xF3);
        graphics.setColor(base);
        graphics.fillRect(0, 0, width, height);

        graphics.setColor(new Color(0xC7, 0xD4, 0xE6));
        graphics.fillRoundRect(-70, 70, 230, 150, 18, 18);
        graphics.setColor(new Color(0xD9, 0xCF, 0xE8));
        graphics.fillRoundRect(width - 130, height - 230, 210, 170, 18, 18);
        graphics.setColor(new Color(0xCF, 0xE3, 0xD8));
        graphics.fillRoundRect(width / 2 - 50, height - 95, 170, 85, 16, 16);
    }

    /** 菜单在合成图里的落点。放在窗口内部，否则会被图像边界裁掉。 */
    private static final int MENU_X = 128;
    private static final int MENU_Y = 178;

    /**
     * 把右键菜单离屏渲染到独立图层。
     *
     * <p>菜单是独立的重型窗口，不受窗口不透明度影响，所以按 100% 画。
     *
     * <p>优先走**纯离屏**路径：只要把菜单布局出来就能直接 {@code paint()} 到内存图，
     * 全程不碰屏幕。但 {@code JPopupMenu} 在部分 LookAndFeel 下不进入组件层级就画不出内容，
     * 于是保留一条兜底：短暂 {@code setVisible(true)} 把宿主窗口显示出来再 {@code show()} 菜单。
     * 兜底路径仍然**只 paint、不抓屏**，所以无论走哪条路都不会把桌面拍进图片。
     */
    private static BufferedImage renderMenu(CalendarWindow window, int width, int height) {
        try {
            BufferedImage menuLayer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            AtomicReference<JPopupMenu> ref = new AtomicReference<>();

            SwingUtilities.invokeAndWait(() -> {
                JPopupMenu menu = window.createContextMenu();
                menu.setInvoker(window.getContentPane());
                menu.setSize(menu.getPreferredSize());
                menu.doLayout();
                ref.set(menu);
            });

            boolean painted = paintMenu(menuLayer, ref.get(), MENU_X, MENU_Y);
            if (painted) {
                System.out.println("菜单已通过纯离屏路径渲染（未显示任何窗口）");
                return menuLayer;
            }

            // 兜底：需要真实可显示才能布局时，短暂显示宿主窗口。仍然不抓屏。
            System.out.println("纯离屏路径画不出菜单，改用兜底路径（窗口会短暂闪现，但不抓屏）");
            SwingUtilities.invokeAndWait(() -> {
                window.setLocation(80, 30);
                window.setVisible(true);
                ref.get().show(window.getContentPane(), MENU_X, MENU_Y);
            });
            Thread.sleep(800);
            boolean ok = paintMenu(menuLayer, ref.get(), MENU_X, MENU_Y);
            SwingUtilities.invokeAndWait(() -> {
                ref.get().setVisible(false);
                window.setVisible(false);
            });
            return ok ? menuLayer : null;
        } catch (Exception exception) {
            System.out.println("菜单离屏渲染失败（其余部分不受影响）: " + exception);
            return null;
        }
    }

    /** 把菜单画到图层上；返回是否真的画出了内容（用于判断是否要兜底）。 */
    private static boolean paintMenu(BufferedImage layer, JPopupMenu menu, int x, int y)
            throws Exception {
        if (menu == null) return false;
        Graphics2D g = layer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        SwingUtilities.invokeAndWait(() -> {
            g.translate(x, y);
            menu.paint(g);
            g.translate(-x, -y);
        });
        g.dispose();

        // 检查图层上是否出现了不透明像素，判断菜单到底画没画出来。
        for (int py = 0; py < layer.getHeight(); py += 2) {
            for (int px = 0; px < layer.getWidth(); px += 2) {
                if (((layer.getRGB(px, py) >>> 24) & 0xFF) > 8) {
                    return true;
                }
            }
        }
        return false;
    }
}
