package com.calendar.ui;

import com.calendar.model.DayMeta;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 今日卡片：挂件的视觉主体。
 *
 * <p>挂件的价值是"扫一眼就知道"，所以最重要的信息必须占据最醒目的位置。
 * 这张卡片回答三个问题：今天几号星期几、今天是什么日子、还有多久放假。
 *
 * <p>月历模式下本卡片隐藏——那时用户在看整月，农历信息由每个格子和详情条承担。
 */
public final class TodayCard extends RoundedPanel {

    private final JLabel dayNumber = new JLabel();
    private final JLabel weekday = new JLabel();
    private final JLabel lunarLine = new JLabel();
    private final JLabel detailLine = new JLabel();
    private final JLabel countdownLine = new JLabel();

    public TodayCard() {
        super(16, Theme.GROUPED_BACKGROUND);
        setBorder(new EmptyBorder(10, 14, 10, 14));
        setLayout(new BorderLayout(14, 0));

        JPanel dateBlock = Theme.transparentPanel(new GridLayout(0, 1, 0, 0));
        dayNumber.setFont(Theme.FONT.deriveFont(Font.BOLD, 36f));
        dayNumber.setForeground(Theme.BLUE);
        weekday.setFont(Theme.FONT.deriveFont(Font.BOLD, 12f));
        weekday.setForeground(Theme.MUTED);
        dateBlock.add(dayNumber);
        dateBlock.add(weekday);
        add(dateBlock, BorderLayout.WEST);

        JPanel info = Theme.transparentPanel(new GridLayout(0, 1, 0, 3));
        lunarLine.setFont(Theme.FONT.deriveFont(Font.BOLD, 14f));
        lunarLine.setForeground(Theme.INK);
        detailLine.setFont(Theme.FONT.deriveFont(12f));
        detailLine.setForeground(Theme.MUTED);
        countdownLine.setFont(Theme.FONT.deriveFont(Font.BOLD, 12f));
        countdownLine.setForeground(Theme.BLUE);
        info.add(lunarLine);
        info.add(detailLine);
        info.add(countdownLine);
        add(info, BorderLayout.CENTER);
    }

    /**
     * 刷新内容。
     *
     * @param date      选中日期
     * @param meta      该日的农历/节假日元数据
     * @param countdown 假期倒计时，可为空
     */
    public void render(LocalDate date, DayMeta meta, String countdown) {
        dayNumber.setText(String.valueOf(date.getDayOfMonth()));
        weekday.setText(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA)
                + holidayBadge(meta));
        weekday.setForeground(meta.restDay() ? Theme.REST_RED
                : meta.makeUpWorkday() ? Theme.BLUE : Theme.MUTED);
        lunarLine.setText(meta.fullLunar());
        detailLine.setText(meta.detailText());
        // 没有倒计时就退回干支，避免第三行空着导致卡片高度塌陷。
        countdownLine.setText(countdown.isBlank() ? meta.ganZhi() : countdown);
        countdownLine.setForeground(countdown.isBlank() ? Theme.MUTED : Theme.BLUE);
    }

    private static String holidayBadge(DayMeta meta) {
        if (meta.restDay()) return "　休";
        if (meta.makeUpWorkday()) return "　班";
        return "";
    }
}
