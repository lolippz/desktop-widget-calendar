package com.calendar.ui;

import com.calendar.model.RemindOption;
import com.calendar.model.Repeat;
import com.calendar.model.Schedule;
import com.calendar.util.Texts;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.List;

/**
 * 选中日期的详情区：上方是农历/节气摘要条，下方是该日日程列表。
 *
 * <p>两者放在同一个组件里，是因为它们总是被同一次刷新一起更新，拆开只会多一层传参。
 *
 * <p>数据由 {@link #render} 注入，本类不访问数据库，也不持有选中日期。
 */
public final class AgendaPanel extends JPanel {

    /** 面板向外抛的事件。 */
    public interface Listener {
        /** 点击"＋ 添加日程"。 */
        void addRequested();

        /** 点击某条日程，通常打开编辑对话框。 */
        void scheduleOpened(Schedule schedule);
    }

    /** 单条日程的高度（含边框）。挂件模式下这个数字直接决定能看见几条日程。 */
    private static final int ITEM_HEIGHT = 58;

    private final JLabel selectedDateLabel = new JLabel();
    private final JLabel lunarDetailLabel =
            Theme.truncatingLabel("", Theme.FONT.deriveFont(12f), Theme.INK);
    private final JPanel items = new ScrollableColumn(0, 0, 0, 0);
    private final JComponent detailBar;
    private final Listener listener;

    public AgendaPanel(Listener listener) {
        super(new BorderLayout(0, 8));
        this.listener = listener;
        setOpaque(false);
        detailBar = createDetailBar();
        add(detailBar, BorderLayout.NORTH);
        add(createAgendaCard(), BorderLayout.CENTER);
    }

    /**
     * 控制农历摘要条是否显示。
     *
     * <p>今日模式下 {@link TodayCard} 已经承担了农历与倒计时，摘要条会变成重复信息，
     * 隐藏它可以把约 40px 让给日程列表。
     */
    public void setDetailBarVisible(boolean visible) {
        detailBar.setVisible(visible);
        revalidate();
    }

    /** 农历摘要条。 */
    private JComponent createDetailBar() {
        RoundedPanel detail = new RoundedPanel(12, Theme.GROUPED_BACKGROUND);
        detail.setBorder(new EmptyBorder(7, 10, 7, 10));
        detail.setLayout(new BorderLayout());
        detail.add(lunarDetailLabel, BorderLayout.CENTER);
        return detail;
    }

    /** 日程卡片：标题行 + 可滚动列表。 */
    private JComponent createAgendaCard() {
        RoundedPanel agenda = new RoundedPanel(16, Theme.GROUPED_BACKGROUND);
        agenda.setBorder(new EmptyBorder(12, 12, 12, 12));
        agenda.setLayout(new BorderLayout(0, 8));

        JPanel titleRow = Theme.transparentPanel(new BorderLayout());
        selectedDateLabel.setFont(Theme.BOLD);
        selectedDateLabel.setForeground(Theme.INK);
        titleRow.add(selectedDateLabel, BorderLayout.WEST);

        JButton addButton = new JButton("＋ 添加日程");
        addButton.setFont(Theme.BOLD.deriveFont(12f));
        addButton.setForeground(Color.WHITE);
        addButton.setFocusable(false);
        Theme.stylePrimaryButton(addButton);
        addButton.addActionListener(e -> listener.addRequested());
        titleRow.add(addButton, BorderLayout.EAST);
        agenda.add(titleRow, BorderLayout.NORTH);

        items.setOpaque(false);
        JScrollPane scroll = new JScrollPane(items);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        agenda.add(scroll, BorderLayout.CENTER);
        return agenda;
    }

    /**
     * 刷新整块详情区。
     *
     * @param selectedDate 选中日期，用于标题
     * @param detailText   农历摘要条全文
     * @param schedules    该日日程，调用方需已按 {@link Schedule#sortKey()} 排好序
     */
    public void render(LocalDate selectedDate, String detailText, List<Schedule> schedules) {
        selectedDateLabel.setText(selectedDate.format(Texts.DATE_LABEL));
        Theme.setTruncatingText(lunarDetailLabel, detailText);

        items.removeAll();
        if (schedules.isEmpty()) {
            JLabel empty = new JLabel("今天没有日程安排　双击日期可直接新建");
            empty.setForeground(Theme.MUTED);
            empty.setBorder(new EmptyBorder(18, 8, 8, 8));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            items.add(empty);
        } else {
            for (Schedule schedule : schedules) {
                items.add(new AgendaItem(schedule));
                items.add(Box.createVerticalStrut(6));
            }
        }
        items.revalidate();
        items.repaint();
    }

    private final class AgendaItem extends RoundedPanel {
        private AgendaItem(Schedule schedule) {
            super(12, Color.WHITE);
            setLayout(new BorderLayout(8, 0));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ITEM_HEIGHT));
            setBorder(new EmptyBorder(8, 10, 8, 10));

            JPanel accent = new JPanel();
            accent.setBackground(Theme.BLUE);
            accent.setPreferredSize(new Dimension(3, 1));
            add(accent, BorderLayout.WEST);

            JPanel copy = Theme.transparentPanel(new GridLayout(0, 1, 0, 2));
            copy.add(Theme.truncatingLabel(schedule.title(), Theme.BOLD, Theme.INK));
            copy.add(Theme.truncatingLabel(subtitle(schedule),
                    Theme.FONT.deriveFont(12f), Theme.MUTED));
            add(copy, BorderLayout.CENTER);

            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    listener.scheduleOpened(schedule);
                }
            });
        }

        /** 副标题：时间 + 重复 + 提醒 + 地点，用中点分隔。 */
        private String subtitle(Schedule schedule) {
            StringBuilder detail = new StringBuilder(schedule.allDay() ? "全天" : schedule.timeRangeText());
            if (schedule.repeat() != Repeat.NONE) {
                detail.append("　·　").append(schedule.repeat());
            }
            if (schedule.remindMinutes() >= 0) {
                detail.append("　·　").append(RemindOption.fromStored(schedule.remindMinutes()).label());
            }
            if (!Texts.valueOr(schedule.location(), "").isBlank()) {
                detail.append("　·　").append(schedule.location());
            }
            return detail.toString();
        }
    }
}
