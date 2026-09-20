package com.calendar.model;

/**
 * 提醒偏移量。入库存分钟数，-1 表示不提醒。
 *
 * <p>初版把中文文案直接当值存，这里保留 {@link #fromLegacy(String)} 用于迁移旧数据。
 */
public record RemindOption(int minutes, String label) {

    public static final RemindOption[] OPTIONS = {
            new RemindOption(-1, "不提醒"),
            new RemindOption(15, "提前 15 分钟"),
            new RemindOption(60, "提前 1 小时"),
            new RemindOption(1440, "提前 1 天")
    };

    /** 不提醒。 */
    public static final RemindOption NONE = OPTIONS[0];

    @Override public String toString() { return label; }

    /** 未知分钟数一律回落到"不提醒"，避免界面出现空白项。 */
    public static RemindOption fromStored(int minutes) {
        for (RemindOption option : OPTIONS) {
            if (option.minutes() == minutes) return option;
        }
        return NONE;
    }

    /** 旧库中的中文提醒文案转成结构化分钟数。 */
    public static RemindOption fromLegacy(String legacy) {
        if (legacy == null) return NONE;
        return switch (legacy.trim()) {
            case "提前 15 分钟" -> OPTIONS[1];
            case "提前 1 小时" -> OPTIONS[2];
            case "提前 1 天" -> OPTIONS[3];
            default -> NONE;
        };
    }
}
