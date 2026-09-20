package com.calendar.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * 圆角实心面板，卡片与分组容器的基类。
 *
 * <p>保持 {@code setOpaque(false)}，先自绘圆角底再交给父类画子组件，
 * 这样圆角之外的区域是透明的，桌面壁纸可以透出来。
 */
public class RoundedPanel extends JPanel {

    private final int radius;
    private final Color fill;

    /** 是否在右下角画"可拖动调整大小"提示纹。 */
    private boolean cornerGrip;

    public RoundedPanel(int radius, Color fill) {
        this.radius = radius;
        this.fill = fill;
        setOpaque(false);
    }

    /**
     * 打开/关闭右下角的调整大小提示纹。
     *
     * <p>只给窗口最外层的那张卡片用。它是个"告知能力存在"的视觉暗示，
     * 内层的分组卡片没有拖拽语义，画上反而误导。
     */
    public void setCornerGripVisible(boolean visible) {
        cornerGrip = visible;
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), radius, radius));
        g2.dispose();
        super.paintComponent(graphics);
        // 提示纹画在子组件之后，否则会被议程卡片盖住。
        if (cornerGrip) {
            Theme.paintResizeGrip((Graphics2D) graphics, getWidth(), getHeight(), 4, Theme.GRIP);
        }
    }
}
