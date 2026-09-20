package com.calendar.model;

/**
 * 一条行情快照。
 *
 * <p>{@code code} 用的是数据源原生代码（{@code sh000001} / {@code gb_ndx}），
 * 不是纯数字代码——A 股、美股、港股在同一个接口里靠前缀区分市场，
 * 丢掉前缀就再也还原不出该去哪个市场取数。
 *
 * @param code          数据源代码，如 {@code sh000001}、{@code gb_ndx}
 * @param name          显示名，如"上证指数"
 * @param price         最新价（休市时即最后收盘价）
 * @param change        涨跌额，与 {@code price} 同量纲
 * @param changePercent 涨跌幅，单位为百分比（{@code 0.94} 表示 +0.94%）
 * @param market        所属市场，用于分组显示与判断交易时段
 */
public record Quote(String code, String name, double price, double change,
                    double changePercent, Market market) {

    /** 市场归属。不同市场的交易时段不同，展示时的状态文案也不同。 */
    public enum Market {
        /** 沪市、深市。 */
        CHINA("A股"),
        /** 美股（含纳斯达克、道琼斯等指数）。 */
        UNITED_STATES("美股"),
        /** 港股。 */
        HONG_KONG("港股"),
        /** 无法归类的代码。 */
        UNKNOWN("其他");

        private final String label;

        Market(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public boolean up() {
        return change > 0;
    }

    public boolean down() {
        return change < 0;
    }

    /**
     * 涨跌幅的显示文本，始终带正负号。
     *
     * <p>挂件上的数字是给"扫一眼"用的，缺了符号就得靠颜色判断方向，
     * 而颜色在低不透明度下会变淡，不够可靠。
     */
    public String percentText() {
        return String.format("%+.2f%%", changePercent);
    }

    /**
     * 价格显示文本。
     *
     * <p>指数动辄上万、个股只有个位数，固定小数位会让指数看起来过于精确，
     * 所以按量级分档：一万以上取整、一千以上一位小数、其余两位。
     */
    public String priceText() {
        double absolute = Math.abs(price);
        if (absolute >= 10000) return String.format("%.0f", price);
        if (absolute >= 1000) return String.format("%.1f", price);
        return String.format("%.2f", price);
    }
}
