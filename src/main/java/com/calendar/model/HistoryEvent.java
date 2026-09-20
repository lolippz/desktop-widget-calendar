package com.calendar.model;

/**
 * "历史上的今天"里的一条事件。
 *
 * @param year        事件发生年份，形如 {@code "1842"}
 * @param title       事件标题
 * @param description 事件简述，可能为空
 * @param link        相关链接（百科词条），可能为空
 */
public record HistoryEvent(String year, String title, String description, String link) {

    /** 列表里显示的主标题：年份 + 标题。 */
    public String displayTitle() {
        return year == null || year.isBlank() ? title : year + "年 · " + title;
    }
}
