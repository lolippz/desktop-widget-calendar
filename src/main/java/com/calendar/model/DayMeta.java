package com.calendar.model;

/**
 * 单个日期在月历网格与详情栏上的渲染元数据。
 *
 * @param cellText            网格小字：优先显示节日/节气，无则显示农历日
 * @param fullLunar           完整农历日期，例如 "八月十七"
 * @param ganZhi              干支纪年/月/日
 * @param detailText          详情栏摘要
 * @param restDay             法定休息日
 * @param makeUpWorkday       调休上班日（周末但要上班）
 * @param holidayDataMissing  该年份的法定节假日数据缺失
 */
public record DayMeta(String cellText, String fullLunar, String ganZhi, String detailText,
                      boolean restDay, boolean makeUpWorkday, boolean holidayDataMissing) { }
