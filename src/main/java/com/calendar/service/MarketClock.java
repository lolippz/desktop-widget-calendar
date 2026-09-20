package com.calendar.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 交易时段判定。
 *
 * <p>存在的意义是决定两件事：多久刷新一次、以及界面上该标注什么状态。
 *
 * <p>一个交易日里绝大部分时间市场是关着的——A 股每天只开 4 小时，美股 6.5 小时。
 * 如果不管时段一律 60 秒刷一次，深夜里的请求全部是无用功，还会平白增加
 * 被数据源限流的风险；反过来，如果一律低频刷新，开盘时用户看到的就是过期数字。
 *
 * <p>时段用的是<b>北京时间</b>。美股按 21:30–05:00 估算，没有区分夏令时与冬令时——
 * 挂件只关心"这个时间点值不值得高频刷新"，差一个小时不影响这个判断，
 * 而引入时区规则表会让这个类复杂好几倍。
 *
 * <p>节假日不判断。国内节假日要靠 lunar 的假日数据推算，而美股节假日是另一套；
 * 判断错了的代价只是"在休市日多刷了几次"，比"在交易日少刷了"轻得多，
 * 所以这里只做保守的粗判。
 */
public final class MarketClock {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final LocalTime CHINA_MORNING_OPEN = LocalTime.of(9, 30);
    private static final LocalTime CHINA_MORNING_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime CHINA_AFTERNOON_OPEN = LocalTime.of(13, 0);
    private static final LocalTime CHINA_AFTERNOON_CLOSE = LocalTime.of(15, 0);

    private static final LocalTime HONG_KONG_AFTERNOON_CLOSE = LocalTime.of(16, 0);

    /** 美股：北京时间晚间开盘，次日凌晨收盘。 */
    private static final LocalTime US_OPEN = LocalTime.of(21, 30);
    private static final LocalTime US_CLOSE = LocalTime.of(5, 0);

    /** 交易时段的刷新间隔。挂件不是盯盘工具，一分钟一次足够。 */
    private static final int ACTIVE_REFRESH_SECONDS = 60;

    /** 休市时的刷新间隔。三十分钟一次，只为让"隔夜跳空"能在第二天早上被看到。 */
    private static final int IDLE_REFRESH_SECONDS = 1800;

    private MarketClock() { }

    /** 当前是否处于任一市场的交易时段。 */
    public static boolean anyMarketOpen() {
        LocalDateTime now = LocalDateTime.now(BEIJING);
        return chinaOpen(now) || hongKongOpen(now) || unitedStatesOpen(now);
    }

    /** 按当前时段给出建议的刷新间隔（秒）。 */
    public static int refreshSeconds() {
        return anyMarketOpen() ? ACTIVE_REFRESH_SECONDS : IDLE_REFRESH_SECONDS;
    }

    /**
     * 状态文案，用于在行情区标注"这个数字是不是活的"。
     *
     * <p>休市时显示收盘数据本身没有错，错的是让用户以为它是实时的。
     */
    public static String statusText() {
        LocalDateTime now = LocalDateTime.now(BEIJING);
        if (chinaOpen(now)) return "A股交易中";
        if (hongKongOpen(now)) return "港股交易中";
        if (unitedStatesOpen(now)) return "美股交易中";
        return "休市中 · 显示最近收盘";
    }

    static boolean chinaOpen(LocalDateTime now) {
        if (isWeekend(now)) return false;
        LocalTime time = now.toLocalTime();
        return inRange(time, CHINA_MORNING_OPEN, CHINA_MORNING_CLOSE)
                || inRange(time, CHINA_AFTERNOON_OPEN, CHINA_AFTERNOON_CLOSE);
    }

    static boolean hongKongOpen(LocalDateTime now) {
        if (isWeekend(now)) return false;
        LocalTime time = now.toLocalTime();
        return inRange(time, CHINA_MORNING_OPEN, LocalTime.NOON)
                || inRange(time, CHINA_AFTERNOON_OPEN, HONG_KONG_AFTERNOON_CLOSE);
    }

    /**
     * 美股时段跨越午夜：周一到周五的 21:30 之后，以及周二到周六的 05:00 之前。
     *
     * <p>凌晨那段归属前一个交易日，所以周六凌晨仍算在周五盘内。
     */
    static boolean unitedStatesOpen(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        if (time.isBefore(US_CLOSE)) {
            // 凌晨：周一凌晨属于上周五盘的延续，不算。
            return day != DayOfWeek.MONDAY && day != DayOfWeek.SUNDAY;
        }
        if (!time.isBefore(US_OPEN)) {
            return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        }
        return false;
    }

    private static boolean inRange(LocalTime time, LocalTime open, LocalTime close) {
        return !time.isBefore(open) && time.isBefore(close);
    }

    private static boolean isWeekend(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
