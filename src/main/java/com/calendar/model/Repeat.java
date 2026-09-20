package com.calendar.model;

/**
 * 重复规则。
 *
 * <p>入库存 {@link #name()}，显示走 {@link #toString()}，避免把界面文案当机器值——
 * 初版直接把中文写进数据库，导致改文案就等于改数据格式。
 */
public enum Repeat {
    NONE("不重复"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月"), YEARLY("每年");

    private final String label;

    Repeat(String label) { this.label = label; }

    @Override public String toString() { return label; }

    /** 兼容初版写入的中文值，以及手工改库可能出现的未知值。 */
    public static Repeat fromStored(String value) {
        if (value == null) return NONE;
        String trimmed = value.trim();
        for (Repeat repeat : values()) {
            if (repeat.name().equalsIgnoreCase(trimmed) || repeat.label.equals(trimmed)) return repeat;
        }
        return NONE;
    }
}
