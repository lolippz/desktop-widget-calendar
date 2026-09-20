package com.calendar.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * 无边框窗口的几何计算。
 *
 * <p>全部是静态纯函数，不读也不写任何窗口状态。抽出来单独放有两个理由：
 * 一是"拖到多近算贴边""缩到多小算最小"这类规则可以脱离界面穷举测试，
 * 二是 {@link CalendarWindow} 只剩编排逻辑，不再把坐标运算和业务混在一起。
 *
 * <p>坐标系约定：{@code point} 是相对窗口左上角的坐标，{@code location}/{@code bounds}
 * 是屏幕坐标，{@code area} 是屏幕可用区域（已扣掉任务栏）。
 */
public final class WindowGeometry {

    /** 热区掩码：没有命中任何边缘。 */
    public static final int ZONE_NONE = 0;
    public static final int ZONE_NORTH = 1;
    public static final int ZONE_SOUTH = 2;
    public static final int ZONE_WEST = 4;
    public static final int ZONE_EAST = 8;

    private WindowGeometry() { }

    // ---------------------------------------------------------------- 热区

    /**
     * 判断坐标落在哪个调整大小热区。
     *
     * <p>用位掩码而不是枚举：角落天然是"两个方向同时命中"，位或可以直接表达，
     * 判定和后续的尺寸计算都不需要为八个方向各写一个分支。
     *
     * @param point  相对窗口左上角的坐标
     * @param size   窗口当前尺寸
     * @param margin 热区厚度
     * @return {@link #ZONE_NONE}，或若干 {@code ZONE_*} 的位或
     */
    public static int resizeZoneAt(Point point, Dimension size, int margin) {
        if (point.x < 0 || point.y < 0 || point.x >= size.width || point.y >= size.height) {
            return ZONE_NONE;
        }
        // 窗口被压到不足两倍热区时内外会重叠，此时整窗都算边缘，避免出现"抓不到"的死区。
        int vertical = Math.min(margin, size.height / 2);
        int horizontal = Math.min(margin, size.width / 2);
        int zone = ZONE_NONE;
        if (point.y < vertical) {
            zone |= ZONE_NORTH;
        } else if (point.y >= size.height - vertical) {
            zone |= ZONE_SOUTH;
        }
        if (point.x < horizontal) {
            zone |= ZONE_WEST;
        } else if (point.x >= size.width - horizontal) {
            zone |= ZONE_EAST;
        }
        return zone;
    }

    /** 热区对应的鼠标光标类型（{@link Cursor} 的 {@code *_RESIZE_CURSOR} 常量）。 */
    public static int resizeCursor(int zone) {
        if (zone == ZONE_NONE) return Cursor.DEFAULT_CURSOR;
        boolean north = (zone & ZONE_NORTH) != 0;
        boolean south = (zone & ZONE_SOUTH) != 0;
        boolean west = (zone & ZONE_WEST) != 0;
        boolean east = (zone & ZONE_EAST) != 0;
        if (north && west) return Cursor.NW_RESIZE_CURSOR;
        if (north && east) return Cursor.NE_RESIZE_CURSOR;
        if (south && west) return Cursor.SW_RESIZE_CURSOR;
        if (south && east) return Cursor.SE_RESIZE_CURSOR;
        if (north) return Cursor.N_RESIZE_CURSOR;
        if (south) return Cursor.S_RESIZE_CURSOR;
        if (west) return Cursor.W_RESIZE_CURSOR;
        return Cursor.E_RESIZE_CURSOR;
    }

    // ---------------------------------------------------------------- 尺寸

    /**
     * 计算拖拽后的窗口矩形。
     *
     * <p>关键在于"锚定"：拖左边或上边时窗口原点要跟着动，但对面那条边必须纹丝不动。
     * 所以宽度先算出来，原点再反推（{@code start.x + start.width - width}），
     * 而不是直接把原点加上位移——后者在触到最小尺寸时会让整个窗口滑走。
     *
     * @param start   按下时的窗口矩形
     * @param zone    按下时命中的热区
     * @param dx      鼠标相对按下点的水平位移
     * @param dy      鼠标相对按下点的垂直位移
     * @param minimum 尺寸下限
     * @param maximum 尺寸上限
     */
    public static Rectangle resizedBounds(Rectangle start, int zone, int dx, int dy,
                                          Dimension minimum, Dimension maximum) {
        int x = start.x;
        int y = start.y;
        int width = start.width;
        int height = start.height;

        if ((zone & ZONE_EAST) != 0) {
            width = clamp(start.width + dx, minimum.width, maximum.width);
        } else if ((zone & ZONE_WEST) != 0) {
            width = clamp(start.width - dx, minimum.width, maximum.width);
            x = start.x + start.width - width;
        }
        if ((zone & ZONE_SOUTH) != 0) {
            height = clamp(start.height + dy, minimum.height, maximum.height);
        } else if ((zone & ZONE_NORTH) != 0) {
            height = clamp(start.height - dy, minimum.height, maximum.height);
            y = start.y + start.height - height;
        }
        return new Rectangle(x, y, width, height);
    }

    /**
     * 调整大小时把"正在拖的那条边"吸附到屏幕可用区域的边缘。
     *
     * <p>只吸被拖的边，另一条边保持锚定——否则窗口会在吸附的瞬间整体平移。
     * 也正因如此这里不能复用 {@link #snappedLocation}：那个函数假设窗口尺寸不变。
     *
     * <p>结果可能超出上下限（例如贴边后变得比最大尺寸还大），调用方需再用
     * {@link #clampSize} 收一次。
     */
    public static Rectangle snappedResize(Rectangle bounds, int zone, Rectangle area) {
        int x = bounds.x;
        int y = bounds.y;
        int width = bounds.width;
        int height = bounds.height;

        if ((zone & ZONE_EAST) != 0) {
            if (Math.abs(x + width - (area.x + area.width)) <= Theme.SNAP_DISTANCE) {
                width = area.x + area.width - x;
            }
        } else if ((zone & ZONE_WEST) != 0) {
            if (Math.abs(x - area.x) <= Theme.SNAP_DISTANCE) {
                width = x + width - area.x;
                x = area.x;
            }
        }
        if ((zone & ZONE_SOUTH) != 0) {
            if (Math.abs(y + height - (area.y + area.height)) <= Theme.SNAP_DISTANCE) {
                height = area.y + area.height - y;
            }
        } else if ((zone & ZONE_NORTH) != 0) {
            if (Math.abs(y - area.y) <= Theme.SNAP_DISTANCE) {
                height = y + height - area.y;
                y = area.y;
            }
        }
        return new Rectangle(x, y, width, height);
    }

    /**
     * 把窗口尺寸限制在"不小于下限、不超过可用区域"之间。
     *
     * <p>可用区域比下限还小时（极小分辨率或超大缩放）以下限为准——
     * 宁可让窗口略微超出屏幕，也不要把它压成一个看不出内容的方块。
     */
    public static Dimension clampSize(Dimension size, Dimension minimum, Rectangle area) {
        return new Dimension(
                clamp(size.width, minimum.width, Math.max(minimum.width, area.width)),
                clamp(size.height, minimum.height, Math.max(minimum.height, area.height)));
    }

    /** 计算窗口所在显示器的可用区域：扣掉任务栏，否则"吸到底部"会把窗口塞到任务栏下面。 */
    public static Rectangle usableArea(Rectangle screenBounds, java.awt.Insets insets) {
        return new Rectangle(screenBounds.x + insets.left, screenBounds.y + insets.top,
                screenBounds.width - insets.left - insets.right,
                screenBounds.height - insets.top - insets.bottom);
    }

    /**
     * 把窗口平移回可用区域内，只挪位置不改尺寸。
     *
     * <p>用于"窗口被迫长高"之后收尾：窗口变高可能把下边缘顶到任务栏底下，
     * 但尺寸是刚算出来的、不该再动，所以只能平移。
     *
     * <p>窗口比可用区域还大时不强行塞进去——那种情况下平移反而会把上边缘推出屏幕。
     */
    public static Rectangle keepInside(Rectangle bounds, Rectangle area) {
        int maxX = area.x + area.width - bounds.width;
        int maxY = area.y + area.height - bounds.height;
        return new Rectangle(
                maxX < area.x ? bounds.x : clamp(bounds.x, area.x, maxX),
                maxY < area.y ? bounds.y : clamp(bounds.y, area.y, maxY),
                bounds.width, bounds.height);
    }

    /**
     * 计算拖动结束后的吸附位置。
     *
     * <p>水平与垂直方向各自独立判定，因此角落可以同时吸两边。
     */
    public static Point snappedLocation(Point location, Dimension size, Rectangle area) {
        int x = location.x;
        int y = location.y;
        if (Math.abs(x - area.x) <= Theme.SNAP_DISTANCE) {
            x = area.x;
        } else if (Math.abs(x + size.width - (area.x + area.width)) <= Theme.SNAP_DISTANCE) {
            x = area.x + area.width - size.width;
        }
        if (Math.abs(y - area.y) <= Theme.SNAP_DISTANCE) {
            y = area.y;
        } else if (Math.abs(y + size.height - (area.y + area.height)) <= Theme.SNAP_DISTANCE) {
            y = area.y + area.height - size.height;
        }
        return new Point(x, y);
    }

    public static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
