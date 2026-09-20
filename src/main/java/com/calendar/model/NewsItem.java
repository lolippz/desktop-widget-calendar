package com.calendar.model;

/**
 * 一条财经要闻。
 *
 * @param time   显示用时间，形如 {@code "10:02"}；解析失败时保留原文
 * @param title  标题
 * @param source 来源媒体名，可能为空
 * @param url    原文链接，可能为空
 */
public record NewsItem(String time, String title, String source, String url) {

    /** 来源为空时不留一个多余的分隔点。 */
    public String sourceText() {
        return source == null || source.isBlank() ? "" : source;
    }
}
