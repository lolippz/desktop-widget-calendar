package com.calendar.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * 视觉常量与通用控件工厂。
 *
 * <p>配色、字体、圆角、按钮风格全部集中在这里，换皮肤只需要动这一个文件。
 * 调色板参考 Apple Human Interface Guidelines。
 */
public final class Theme {

    public static final Color BLUE = new Color(0, 122, 255);
    public static final Color INK = new Color(28, 28, 30);
    public static final Color MUTED = new Color(142, 142, 147);
    public static final Color GROUPED_BACKGROUND = new Color(242, 242, 247, 242);
    public static final Color REST_RED = new Color(220, 45, 55);
    public static final Color DANGER = new Color(255, 59, 48);
    /** 法定休息日的格子底色。 */
    public static final Color REST_TINT = new Color(255, 240, 241);
    /** 调休上班日的格子底色。 */
    public static final Color MAKEUP_TINT = new Color(242, 245, 250);

    /**
     * 可选的窗口不透明度档位（百分比）。
     *
     * <p>下限取 50%：窗口级不透明度会连同文字一起淡出，实测 60% 在花哨的壁纸或
     * 窗口背景下文字已明显发虚，再低就失去"扫一眼"的意义。
     */
    public static final int[] OPACITY_STEPS = {100, 90, 80, 70, 60, 50};

    /** 默认不透明度：看得出是浮在桌面上的挂件，但文字仍然清晰。 */
    public static final int DEFAULT_OPACITY = 92;

    /** 拖动结束后，窗口边缘距屏幕边缘小于该距离即吸附过去。 */
    public static final int SNAP_DISTANCE = 24;

    /** 挂件的初始尺寸。够窄，横着摆不挡桌面。 */
    public static final int DEFAULT_WINDOW_WIDTH = 390;
    public static final int DEFAULT_WINDOW_HEIGHT = 590;

    /**
     * 窗口尺寸下限。
     *
     * <p>宽度下限由标题栏与月历网格共同决定，两种模式不同：
     * 今日模式标题栏只有"月历 + 月份 + 今天 + … + ×"，实测需要 281px；
     * 月历模式还要放下 ‹ › 两个翻月箭头，实测需要 347px。
     */
    public static final int MIN_WINDOW_WIDTH = 340;

    /** 今日模式的高度下限：标题栏 + 今日卡片 + 2 条日程，实测 440px 正好够。 */
    public static final int MIN_WINDOW_HEIGHT = 440;

    /**
     * 月历模式的宽度下限。
     *
     * <p>比今日模式宽，因为标题栏多了 ‹ › 两个箭头（标题栏用 {@code BorderLayout}，
     * 左右两组子项各自按首选宽度占位，宽度不够时会直接叠在一起，不会自动省略）。
     */
    public static final int MIN_MONTH_MODE_WINDOW_WIDTH = 360;

    /**
     * 月历模式的高度下限。
     *
     * <p>必须比今日模式高：6 行日期网格是固定高度且不可压缩，在 440px 的总高里它会把
     * 议程列表挤到只剩 4px——等于没有。实测 570px 才能给议程列表留下 2 条的位置。
     */
    public static final int MIN_MONTH_MODE_WINDOW_HEIGHT = 570;

    /**
     * 边缘/角落的调整大小热区厚度。
     *
     * <p>6px 是"好抓"和"不侵占内容"的折中：再薄鼠标要对得很准，再厚会盖住卡片内侧。
     */
    public static final int RESIZE_MARGIN = 6;

    /** 右下角"可拉伸"提示纹的颜色。要足够淡，只作暗示不抢视线。 */
    public static final Color GRIP = new Color(186, 186, 192);

    /**
     * 行情涨跌配色：<b>涨红跌绿</b>。
     *
     * <p>刻意与欧美市场相反。这个挂件看的是 A 股大盘和国内用户的自选股，
     * 沿用本地习惯比追求"国际化"更重要——颜色反了会让人在扫一眼的瞬间读错方向，
     * 而扫一眼正是这个挂件唯一的交互方式。
     */
    public static final Color QUOTE_UP = new Color(214, 48, 49);
    public static final Color QUOTE_DOWN = new Color(0, 148, 68);
    /** 平盘（涨跌额恰为 0）用中性灰，不要用红或绿暗示方向。 */
    public static final Color QUOTE_FLAT = MUTED;

    /**
     * 今日模式下附加内容各自占用的高度（含区块之间的间距）。
     *
     * <p>用来动态抬高窗口的最小高度：附加内容开着时，440px 只能容下一条日程，
     * 那已经不算"能用"了——挂件的核心价值是看到今天要做什么。关掉开关后
     * 最小高度回落到 {@link #MIN_WINDOW_HEIGHT}，用户仍能把窗口缩到很小。
     *
     * <p>数字来自 {@code LayoutProbe} 的实测（行情条 19px 内容 + 10px 间距，
     * 历史入口 27px + 10px 间距）。改动了 {@link QuoteTicker} 或
     * {@link HistoryStrip} 的内边距之后，需要同步更新这两个值。
     */
    public static final int QUOTE_BLOCK_HEIGHT = 29;
    public static final int HISTORY_BLOCK_HEIGHT = 37;

    // Microsoft YaHei UI has complete Simplified-Chinese glyph coverage on Windows.
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    public static final Font BOLD = FONT.deriveFont(Font.BOLD, 14);

    /** AWT 原生菜单不跟随 Swing 字体，需要单独指定。 */
    public static final Font NATIVE_MENU_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);

    private Theme() { }

    /** 安装外观与全局控件默认值。必须在创建任何 Swing 组件之前调用。 */
    public static void install() {
        FlatLightLaf.setup();
        UIManager.put("defaultFont", FONT);
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 999);
        UIManager.put("TextComponent.arc", 10);
    }

    /** 透明容器：桌面卡片里几乎所有中间层都只是布局壳，不该有底色。 */
    public static JPanel transparentPanel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /** 顶部导航用的字形按钮（‹ › ×）。 */
    public static JButton iconButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(FONT.deriveFont(22f));
        button.setToolTipText(tooltip);
        button.setForeground(INK);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setMargin(new Insets(0, 5, 0, 5));
        return button;
    }

    /** 无边框的文字按钮（"今天"）。 */
    public static JButton textButton(String text) {
        JButton button = new JButton(text);
        button.setFont(FONT.deriveFont(12f));
        button.setForeground(BLUE);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        // 外观默认的左右内边距是 14px，两个字的按钮会被撑到 72px 宽。
        // 标题栏在窄窗口里本来就挤，这笔开销没有换来任何可读性，收掉。
        button.setMargin(new Insets(2, 8, 2, 8));
        return button;
    }

    /** 主操作按钮（"保存""＋ 添加日程"）。 */
    public static void stylePrimaryButton(JButton button) {
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setBackground(BLUE);
        button.setForeground(Color.WHITE);
        button.setBorder(new EmptyBorder(7, 11, 7, 11));
    }

    /** 视图切换按钮（"今日"/"月历"）。 */
    public static JButton toggleButton(String text) {
        JButton button = textButton(text);
        button.setFont(FONT.deriveFont(Font.BOLD, 12f));
        return button;
    }

    /**
     * 宽度不足时按省略号截断的标签，全文放进 tooltip。
     *
     * <p>截断依赖真实宽度，所以监听 {@code componentResized} 而不是在构造时算一次。
     */
    public static JLabel truncatingLabel(String text, Font font, Color color) {
        JLabel label = new JLabel();
        label.setFont(font);
        label.setForeground(color);
        setTruncatingText(label, text);
        label.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { applyTruncation(label); }
        });
        return label;
    }

    /** 更新截断标签的全文并立即重算省略。全文始终保留在 tooltip 里。 */
    public static void setTruncatingText(JLabel label, String text) {
        label.putClientProperty("fullText", text);
        label.setToolTipText(text);
        applyTruncation(label);
    }

    /** 二分查找能放下的最长前缀，避免逐字符裁剪带来的宽度抖动。 */
    private static void applyTruncation(JLabel label) {
        Object full = label.getClientProperty("fullText");
        if (full == null) return;
        String text = full.toString();
        int width = label.getWidth();
        if (width <= 0) {
            label.setText(text);
            return;
        }
        FontMetrics metrics = label.getFontMetrics(label.getFont());
        if (metrics.stringWidth(text) <= width) {
            label.setText(text);
            return;
        }
        String ellipsis = "…";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (metrics.stringWidth(text.substring(0, mid) + ellipsis) <= width) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        label.setText(low <= 0 ? ellipsis : text.substring(0, low) + ellipsis);
    }

    /**
     * 在给定宽度内水平居中绘制文本。
     *
     * <p>宽度必须由调用方显式传入：{@code paintComponent} 里若用
     * {@code getClipBounds()} 取宽度，会被父容器的裁剪区污染，居中会算错。
     */
    public static void drawCentered(Graphics2D graphics, String text, int baseline,
                                    Font font, Color color, int width) {
        graphics.setFont(font);
        graphics.setColor(color);
        int textWidth = graphics.getFontMetrics().stringWidth(text);
        graphics.drawString(text, (width - textWidth) / 2, baseline);
    }

    /**
     * 在右下角画"可以拖动调整大小"的提示纹（三道斜杠）。
     *
     * <p>无边框窗口没有任何原生边框，不画这个提示，用户根本不知道窗口能拉伸——
     * 和"看不出卡片能拖动"是同一类可发现性问题。
     *
     * @param inset 纹样距右下角的距离
     */
    public static void paintResizeGrip(Graphics2D graphics, int width, int height,
                                       int inset, Color color) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i <= 3; i++) {
            int reach = inset + i * 4;
            g2.drawLine(width - reach, height - inset, width - inset, height - reach);
        }
        g2.dispose();
    }

    /** 窗口的默认尺寸与最小尺寸。集中在这里，避免各处重复拼 {@code new Dimension}。 */
    public static Dimension defaultWindowSize() {
        return new Dimension(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    }

    /**
     * 当前视图模式下的窗口尺寸下限。
     *
     * <p>两种模式的下限不同：月历模式的标题栏多了翻月箭头、正文多了不可压缩的 6 行网格，
     * 能压到的最小尺寸更大。把差异收在这里，调用方就不必自己判断"现在是哪个模式"。
     */
    public static Dimension minimumWindowSize(boolean monthMode) {
        return monthMode
                ? new Dimension(MIN_MONTH_MODE_WINDOW_WIDTH, MIN_MONTH_MODE_WINDOW_HEIGHT)
                : new Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
    }
}
