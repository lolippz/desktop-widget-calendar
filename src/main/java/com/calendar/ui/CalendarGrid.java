package com.calendar.ui;

import com.calendar.model.DayMeta;
import com.calendar.service.LunarService;
import com.calendar.util.Texts;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.function.Function;

/**
 * 月历网格：星期表头 + 6×7 日期按钮，支持滚轮切换月份。
 *
 * <p>只负责渲染与事件外发，不持有选中状态，也不访问数据库。
 * 农历信息由构造时注入的 {@code metaProvider} 提供，方便替换或做假数据预览。
 */
public final class CalendarGrid extends JPanel {

    /** 网格向外抛的事件。 */
    public interface Listener {
        /** 单击或双击某天。{@code clickCount} 为 1 表示选中，2 表示新建。 */
        void dateClicked(LocalDate date, int clickCount);

        /** 滚轮请求切换月份，负值向前、正值向后。 */
        void monthShiftRequested(int delta);
    }

    private static final String[] WEEKDAY_NAMES = {"日", "一", "二", "三", "四", "五", "六"};

    private final JPanel grid = new JPanel(new GridLayout(6, 7, 2, 2));
    private final Function<LocalDate, DayMeta> metaProvider;
    private final Listener listener;

    public CalendarGrid(Function<LocalDate, DayMeta> metaProvider, Listener listener) {
        super(new BorderLayout(0, 6));
        this.metaProvider = metaProvider;
        this.listener = listener;
        setOpaque(false);

        JPanel weekdayNames = new JPanel(new GridLayout(1, 7));
        weekdayNames.setOpaque(false);
        for (String name : WEEKDAY_NAMES) {
            JLabel label = new JLabel(name, SwingConstants.CENTER);
            label.setFont(Theme.FONT.deriveFont(12f));
            label.setForeground(Theme.MUTED);
            weekdayNames.add(label);
        }
        grid.setOpaque(false);
        add(weekdayNames, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);

        // 滚轮切换月份：桌面挂件的高频操作，不该只依赖箭头按钮。
        // JLabel/JButton 不消费滚轮事件，所以事件会从子组件冒泡到这里。
        addMouseWheelListener((MouseWheelEvent event) ->
                listener.monthShiftRequested(event.getWheelRotation()));
    }

    /**
     * 重建整月网格。
     *
     * <p>调用方需先把整月的日程查出来压成 {@code scheduledDates}，本方法不再触碰数据库。
     */
    public void render(YearMonth month, LocalDate today, LocalDate selected,
                       Set<LocalDate> scheduledDates) {
        grid.removeAll();
        int leadingBlankCount = month.atDay(1).getDayOfWeek().getValue() % 7;
        for (int i = 0; i < leadingBlankCount; i++) {
            grid.add(new JLabel());
        }
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            grid.add(new DayButton(date, today, selected,
                    scheduledDates.contains(date), metaProvider.apply(date)));
        }
        // 固定 6 行，避免切换月份时整张卡片高度跳动。
        for (int i = leadingBlankCount + month.lengthOfMonth(); i < 42; i++) {
            grid.add(new JLabel());
        }
        grid.revalidate();
        grid.repaint();
    }

    private final class DayButton extends JButton {
        private final LocalDate date;
        private final LocalDate today;
        private final LocalDate selected;
        private final boolean hasSchedule;
        private final DayMeta meta;

        private DayButton(LocalDate date, LocalDate today, LocalDate selected,
                          boolean hasSchedule, DayMeta meta) {
            this.date = date;
            this.today = today;
            this.selected = selected;
            this.hasSchedule = hasSchedule;
            this.meta = meta;
            setFont(Theme.FONT);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setMargin(new Insets(0, 0, 0, 0));
            setPreferredSize(new Dimension(44, 36));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(date.format(Texts.DATE_LABEL) + "　双击新建日程");
            // 用鼠标事件而非 ActionListener，才能区分单击（选中）与双击（新建）。
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    listener.dateClicked(date, event.getClickCount());
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean restDay = meta.restDay() || (LunarService.isWeekend(date) && !meta.makeUpWorkday());
            if (meta.restDay()) {
                g2.setColor(Theme.REST_TINT);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 8, 8);
            } else if (meta.makeUpWorkday()) {
                g2.setColor(Theme.MAKEUP_TINT);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 8, 8);
            }
            if (date.equals(today)) {
                g2.setColor(Theme.BLUE);
                g2.fillOval((getWidth() - 26) / 2, 1, 26, 26);
            } else if (date.equals(selected)) {
                g2.setColor(Theme.BLUE);
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
            }
            Color dateColor = date.equals(today) ? Color.WHITE : (restDay ? Theme.REST_RED : Theme.INK);
            Theme.drawCentered(g2, String.valueOf(date.getDayOfMonth()), 19,
                    Theme.FONT.deriveFont(Font.BOLD, 14f), dateColor, getWidth());
            Color lunarColor = date.equals(today) ? Color.WHITE : (restDay ? Theme.REST_RED : Theme.MUTED);
            Theme.drawCentered(g2, meta.cellText(), 32,
                    Theme.FONT.deriveFont(9f), lunarColor, getWidth());
            if (meta.restDay() || meta.makeUpWorkday()) {
                g2.setFont(Theme.FONT.deriveFont(Font.BOLD, 9f));
                g2.setColor(meta.restDay() ? Theme.REST_RED : Theme.BLUE);
                g2.drawString(meta.restDay() ? "休" : "班", 3, 10);
            }
            if (hasSchedule) {
                g2.setColor(date.equals(today) ? Color.WHITE : Theme.BLUE);
                g2.fillOval(getWidth() - 7, 3, 4, 4);
            }
            g2.dispose();
        }
    }
}
