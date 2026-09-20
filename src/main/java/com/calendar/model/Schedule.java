package com.calendar.model;

import com.calendar.util.Texts;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 一条日程。
 *
 * <p>不可变值对象，界面与数据库都围绕它转换。{@code id == 0} 表示尚未入库的新日程。
 * 全天日程的 {@code startTime}/{@code endTime} 为 {@code null}。
 */
public record Schedule(long id, LocalDate date, String title, String location,
                       LocalTime startTime, LocalTime endTime,
                       Repeat repeat, LocalDate repeatUntil,
                       int remindMinutes, String description, boolean allDay) {

    /** 排序键：全天最前，其余按开始时刻，未设时间排最后。 */
    public String sortKey() {
        if (allDay) return "0";
        return startTime == null ? "2" : "1" + startTime.format(Texts.TIME_FORMAT);
    }

    /** 展示用的时间区间文案，例如 {@code "09:00 - 10:30"}。 */
    public String timeRangeText() {
        if (startTime == null) return "未设置时间";
        String text = startTime.format(Texts.TIME_FORMAT);
        if (endTime != null) {
            text += " - " + endTime.format(Texts.TIME_FORMAT);
        }
        return text;
    }
}
