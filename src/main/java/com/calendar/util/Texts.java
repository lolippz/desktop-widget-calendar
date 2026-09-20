package com.calendar.util;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 纯文本工具：日期时间格式化器与空值兜底。
 *
 * <p>这里不放任何业务语义，任何一层都可以安全依赖它。
 */
public final class Texts {

    /** 时刻格式，同时是入库格式。 */
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 日期格式，同时是入库格式（ISO-8601）。 */
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 面向用户的日期标题，例如 "9月17日 星期四"。 */
    public static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);

    private Texts() { }

    /** null 转成兜底值，避免每一处调用点都写三目运算。 */
    public static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** 取列表首项，空列表返回空串。 */
    public static String firstOf(List<String> values) {
        return values == null || values.isEmpty() ? "" : valueOr(values.get(0), "");
    }

    /** 返回第一个非空白值，全为空则返回空串。 */
    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
