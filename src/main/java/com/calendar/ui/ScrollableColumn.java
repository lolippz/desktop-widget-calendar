package com.calendar.ui;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.border.EmptyBorder;
import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * 放进 {@code JScrollPane} 的纵向内容面板，宽度跟随视口而不是内容。
 *
 * <p>存在的唯一理由是 {@link Scrollable#getScrollableTracksViewportWidth()}。
 * 普通 {@code JPanel} 放进滚动面板时，内容宽度取 {@code preferredSize}，
 * 而 {@link Theme#truncatingLabel} 的 {@code preferredSize} 是<b>全文</b>宽度——
 * 这是它工作的前提：它靠真实宽度反算该截到哪里。两者碰在一起就出事：
 * 一条长标题足以把内容撑得比视口还宽，右侧的数字被裁掉，而且因为水平滚动条
 * 是禁用的，用户看到的就只是"数字莫名其妙少了一截"。
 *
 * <p>让宽度跟随视口之后，超长文本由 {@code truncatingLabel} 按实际宽度截断，
 * 这才是它被设计出来的用法。
 *
 * <p>高度不跟随视口：内容比视口矮时不该被拉伸，否则列表会散开。
 */
public final class ScrollableColumn extends JPanel implements Scrollable {

    /** 滚动一次一个"行"的高度，与列表项的高度大致相当。 */
    private static final int UNIT_INCREMENT = 14;

    /** 翻页滚动的距离。 */
    private static final int BLOCK_INCREMENT = 100;

    public ScrollableColumn(int top, int left, int bottom, int right) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(top, left, bottom, right));
    }

    @Override public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return UNIT_INCREMENT;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return BLOCK_INCREMENT;
    }

    /** 关键：内容宽度跟着视口走。 */
    @Override public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** 高度按内容，比视口矮时不拉伸。 */
    @Override public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
