package com.calendar.ui;

import com.calendar.model.HistoryEvent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * "历史上的今天"事件列表弹窗。
 *
 * <p>事件条数通常在 10–20 条之间，且每条都带一段描述，内联进挂件是不现实的——
 * 那是"读"的内容，不是"扫"的内容。弹窗给了它足够的空间，也让挂件本身保持干净。
 */
public final class HistoryDialog extends JDialog {

    private static final Font YEAR_FONT = Theme.FONT.deriveFont(Font.BOLD, 12f);
    private static final Font TITLE_FONT = Theme.FONT.deriveFont(Font.BOLD, 12f);
    private static final Font DESC_FONT = Theme.FONT.deriveFont(11f);

    private static final DateTimeFormatter DATE_TITLE = DateTimeFormatter.ofPattern("M月d日");

    private static HistoryDialog instance;

    private HistoryDialog(Window owner, List<HistoryEvent> events) {
        super(owner, "历史上的今天", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 同 MarketDialog：事件描述是 truncatingLabel，preferredSize 是全文宽度，
        // 用普通 JPanel 会把内容撑宽，右侧的描述文字被裁掉。
        ScrollableColumn content = new ScrollableColumn(14, 16, 14, 16);
        content.setBackground(Color.WHITE);

        JLabel header = new JLabel("历史上的今天 · " + DATE_TITLE.format(LocalDate.now())
                + "　共 " + events.size() + " 条");
        header.setFont(Theme.FONT.deriveFont(Font.BOLD, 12f));
        header.setForeground(Theme.MUTED);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(header);
        content.add(Box.createVerticalStrut(12));

        for (HistoryEvent event : events) {
            content.add(createRow(event));
            content.add(Box.createVerticalStrut(10));
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setContentPane(scroll);

        setSize(450, 520);
        setLocationRelativeTo(owner);
    }

    public static void open(Window owner, List<HistoryEvent> events) {
        if (events == null || events.isEmpty()) return;
        if (instance != null) {
            instance.dispose();
        }
        instance = new HistoryDialog(owner, events);
        instance.setVisible(true);
    }

    private JComponent createRow(HistoryEvent event) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        JLabel year = new JLabel(event.year().isBlank() ? "—" : event.year());
        year.setFont(YEAR_FONT);
        year.setForeground(Theme.BLUE);
        year.setPreferredSize(new Dimension(44, 20));
        year.setVerticalAlignment(JLabel.TOP);
        row.add(year, BorderLayout.WEST);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));

        JLabel title = Theme.truncatingLabel(event.title(), TITLE_FONT, Theme.INK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(title);

        if (!event.description().isBlank()) {
            JLabel desc = Theme.truncatingLabel(condense(event.description()), DESC_FONT, Theme.MUTED);
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            copy.add(desc);
            desc.setToolTipText(event.description());
        }
        row.add(copy, BorderLayout.CENTER);

        if (!event.link().isBlank()) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            String tip = "点击打开相关词条";
            row.setToolTipText(tip);
            row.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent mouseEvent) {
                    browse(event.link());
                }
            });
        }
        return row;
    }

    /** 描述里的换行会把固定行高的布局撑开，压成单行再显示。 */
    private static String condense(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 70 ? flat : flat.substring(0, 70) + "…";
    }

    /** 用系统默认浏览器打开链接。失败就静默忽略——打不开链接不是错误。 */
    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // 链接无效或系统不支持浏览器，忽略。
        }
    }
}
