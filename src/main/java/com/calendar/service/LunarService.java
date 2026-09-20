package com.calendar.service;

import com.calendar.model.DayMeta;
import com.calendar.util.Texts;
import com.nlf.calendar.Holiday;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.nlf.calendar.util.HolidayUtil;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 农历、节气、法定节假日与假期倒计时。
 *
 * <p>依赖 lunar-java 的 {@code HolidayUtil}。该库的法定假日数据按年硬编码，
 * 缺失年份 {@code getHoliday()} 恒返回 null——这会把法定假日静默渲染成普通工作日，
 * 所以对外暴露 {@link #hasHolidayData(int)} 让界面能明确提示。
 */
public final class LunarService {

    /** 按年缓存覆盖情况，避免每次重绘都做两次查表。 */
    private static final Map<Integer, Boolean> HOLIDAY_COVERAGE = new HashMap<>();

    /** 假期倒计时最多向后看的天数，超过一年没有假期说明数据缺失。 */
    private static final int COUNTDOWN_LOOKAHEAD_DAYS = 400;

    private LunarService() { }

    /** 组装一个日期在网格与详情栏上需要的全部信息。 */
    public static DayMeta describe(LocalDate date) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        Lunar lunar = solar.getLunar();
        Holiday holiday = HolidayUtil.getHoliday(date.toString());
        String jieQi = Texts.valueOr(lunar.getJieQi(), "");

        String festival;
        if (holiday != null && holiday.isWork()) {
            // 调休上班日：HolidayUtil 返回的是被补偿的节假日名，直接当标签用会出现
            // "九月的周日显示国庆节"这类语义错误。
            festival = "补班";
        } else if (holiday != null) {
            festival = holiday.getName();
        } else {
            festival = Texts.firstNonBlank(jieQi, Texts.firstOf(lunar.getFestivals()),
                    Texts.firstOf(solar.getFestivals()));
        }

        String lunarDate = lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
        String cellText = festival.isBlank() ? lunarDate : festival;
        String ganZhi = lunar.getYearInGanZhi() + "年 " + lunar.getMonthInGanZhi() + "月 "
                + lunar.getDayInGanZhi() + "日";
        List<String> yiItems = lunar.getDayYi();
        String yi = yiItems == null || yiItems.isEmpty() ? ""
                : String.join(" ", yiItems.subList(0, Math.min(3, yiItems.size())));
        String detail = festival.isBlank() ? (yi.isBlank() ? "日历信息" : "宜 " + yi) : festival;
        return new DayMeta(cellText, lunarDate, ganZhi, detail,
                holiday != null && !holiday.isWork(),
                holiday != null && holiday.isWork(),
                !hasHolidayData(date.getYear()));
    }

    /** lunar-java 的法定假日数据按年硬编码，缺失年份 {@code getHoliday()} 恒返回 null。 */
    public static boolean hasHolidayData(int year) {
        return HOLIDAY_COVERAGE.computeIfAbsent(year, value ->
                HolidayUtil.getHoliday(value + "-01-01") != null
                        || HolidayUtil.getHoliday(value + "-10-01") != null);
    }

    /** 下一个法定假期的倒计时，例如"距离中秋节还有 8 天"。 */
    public static String holidayCountdown(LocalDate from) {
        if (!hasHolidayData(from.getYear())) return "";
        for (int offset = 0; offset <= COUNTDOWN_LOOKAHEAD_DAYS; offset++) {
            LocalDate date = from.plusDays(offset);
            if (date.getYear() != from.getYear() && !hasHolidayData(date.getYear())) return "";
            Holiday holiday = HolidayUtil.getHoliday(date.toString());
            if (holiday == null || holiday.isWork()) continue;
            // 只在假期的第一天提示，避免整个假期都在倒数。
            Holiday previous = HolidayUtil.getHoliday(date.minusDays(1).toString());
            if (previous != null && !previous.isWork() && previous.getName().equals(holiday.getName())) {
                continue;
            }
            if (offset == 0) return "今天起放假 · " + holiday.getName();
            return "距离" + holiday.getName() + "还有 " + offset + " 天";
        }
        return "";
    }

    /** 纯日历意义上的周末，不含调休判定。 */
    public static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6;
    }

    /** 明年的法定节假日数据是否已覆盖，用于启动自检。 */
    public static boolean isNextYearCovered() {
        return hasHolidayData(LocalDate.now().getYear() + 1);
    }
}
