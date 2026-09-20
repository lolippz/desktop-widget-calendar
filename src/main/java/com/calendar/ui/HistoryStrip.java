package com.calendar.ui;

import com.calendar.model.HistoryEvent;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * "历史上的今天"入口条。
 *
 * <p>只占一行，且把首条事件作为预览显示出来——纯入口（只写"历史上的今天"）没人会点，
 * 因为它没有告诉用户点开能得到什么。给一个具体的事件标题，好奇心才会起作用。
 *
 * <p>没有数据时整条隐藏，而不是显示"暂无数据"：这个区块本身是可选内容，
 * 让出一行高度给日程列表是更好的选择。
 *
 * <p>取不到数据只发生在网络故障或数据源失效时，且缓存能覆盖大部分情况，
 * 所以不需要额外的错误提示。
 */
public final class HistoryStrip extends RoundedPanel {

    /** 点击时打开详情弹窗。 */
    public interface Listener {
        void openRequested(List<HistoryEvent> events);
    }

    private static final Font TITLE_FONT = Theme.FONT.deriveFont(Font.BOLD, 11f);
    private static final Font PREVIEW_FONT = Theme.FONT.deriveFont(11f);
    private static final Font ARROW_FONT = Theme.FONT.deriveFont(Font.BOLD, 13f);

    private final Listener listener;
    private final JLabel preview = Theme.truncatingLabel("", PREVIEW_FONT, Theme.MUTED);
    private final JLabel count = new JLabel();

    private List<HistoryEvent> events = List.of();

    /** 是否允许显示。由主窗口按"用户开关 + 当前视图模式"设置。 */
    private boolean allowed = true;

    public HistoryStrip(Listener listener) {
        super(12, Theme.GROUPED_BACKGROUND);
        this.listener = listener;
        setLayout(new BorderLayout(8, 0));
        setBorder(new javax.swing.border.EmptyBorder(6, 10, 6, 10));

        JLabel title = new JLabel("历史上的今天");
        title.setFont(TITLE_FONT);
        title.setForeground(Theme.INK);
        add(title, BorderLayout.WEST);
        add(preview, BorderLayout.CENTER);

        count.setFont(PREVIEW_FONT);
        count.setForeground(Theme.BLUE);
        add(count, BorderLayout.EAST);

        installClick(this);
        installClick(title);
        installClick(preview);
        installClick(count);
        setVisible(false);
    }

    /** 渲染事件列表。空列表时整条隐藏。 */
    public void render(List<HistoryEvent> events) {
        this.events = events == null ? List.of() : events;
        applyVisibility();
        if (this.events.isEmpty()) {
            return;
        }
        HistoryEvent first = this.events.get(0);
        Theme.setTruncatingText(preview, first.displayTitle());
        count.setText(this.events.size() + " 条 ›");
        String tip = first.displayTitle()
                + (first.description().isBlank() ? "" : "\n" + first.description());
        preview.setToolTipText(tip);
        setToolTipText("查看全部 " + this.events.size() + " 条");
    }

    /**
     * 设置是否允许显示。
     *
     * <p>可见性由"开关"和"有没有数据"共同决定，所以两处变化都要走
     * {@link #applyVisibility()}，否则会出现"开了开关但没数据时露出一个空条"
     * 或者"有数据却因为开关状态变化而没跟上"。
     */
    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        applyVisibility();
    }

    private void applyVisibility() {
        setVisible(allowed && !events.isEmpty());
    }

    /** 供主窗口在切换日期时判断是否需要让出高度。 */
    public boolean hasContent() {
        return !events.isEmpty();
    }

    private void installClick(Component component) {
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!events.isEmpty()) {
                    listener.openRequested(events);
                }
            }
        });
    }
}
