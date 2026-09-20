package com.calendar.ui;

import com.calendar.model.Quote;
import com.calendar.service.MarketClock;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 内联行情条：今日卡片下方那一行涨跌数字。
 *
 * <p>设计上刻意压得很轻：<b>没有底色、没有边框、字号只有 11px</b>。
 * 今日模式下的议程列表已经占满了剩余高度（默认尺寸下视口约 328px，只够放 5 条日程），
 * 任何一块带卡片质感的新内容都会把日程挤掉一两条。行情是"环境信息"，
 * 它应该像背景音一样存在，而不是和日程抢视觉重量。
 *
 * <p>因此这里只做一件事：把最关心的几个标的的涨跌幅摆成一行。想看具体点位、
 * 自选股详情和要闻，点击展开弹窗——那是"主动查看"，值得占用更多空间。
 *
 * <p>非交易时段照常显示收盘数据，但最前面会有一个灰色的状态点，鼠标悬停能看到
 * "休市中 · 显示最近收盘"。不提示的话，用户会以为半夜看到的是实时数字。
 */
public final class QuoteTicker extends JPanel {

    /** 点击行情条时向外抛的事件。 */
    public interface Listener {
        /** 用户想看详情。 */
        void detailsRequested();
    }

    /**
     * 最多显示的标的数。
     *
     * <p>默认宽度下每行放 3 个，8 个约合两行多。设上限是为了防止自选股很多时
     * 行情条无限长高——它在布局里是"固定开销"，长高多少就从日程列表里扣掉多少。
     */
    private static final int MAX_CHIPS = 8;

    private static final Font NAME_FONT = Theme.FONT.deriveFont(11f);
    private static final Font VALUE_FONT = Theme.FONT.deriveFont(Font.BOLD, 11f);

    private final Listener listener;
    private final JPanel chips = Theme.transparentPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
    private final JLabel statusDot = new JLabel();

    public QuoteTicker(Listener listener) {
        super(new BorderLayout(8, 0));
        this.listener = listener;
        setOpaque(false);

        statusDot.setFont(Theme.FONT.deriveFont(9f));
        statusDot.setForeground(Theme.MUTED);
        add(statusDot, BorderLayout.WEST);
        add(chips, BorderLayout.CENTER);
        makeClickable(this);
    }

    /** 首次加载时的占位。不显示转圈，避免挂件看起来一直在忙。 */
    public void showLoading() {
        statusDot.setText("");
        statusDot.setToolTipText(null);
        chips.removeAll();
        chips.add(placeholder("行情加载中…"));
        refresh();
    }

    /** 渲染行情。空列表等同于"取不到"。 */
    public void showQuotes(List<Quote> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            showUnavailable();
            return;
        }
        boolean open = MarketClock.anyMarketOpen();
        statusDot.setText("●");
        statusDot.setForeground(open ? Theme.QUOTE_DOWN : Theme.MUTED);
        statusDot.setToolTipText(MarketClock.statusText());

        chips.removeAll();
        int shown = Math.min(quotes.size(), MAX_CHIPS);
        for (int index = 0; index < shown; index++) {
            chips.add(createChip(quotes.get(index)));
        }
        if (quotes.size() > shown) {
            chips.add(placeholder("+" + (quotes.size() - shown)));
        }
        refresh();
    }

    /**
     * 取不到行情时的显示。
     *
     * <p>不隐藏整条，因为"突然消失"比"显示暂不可用"更让人困惑；
     * 也不弹错误框——这是辅助信息，不值得打断用户正在做的事。
     */
    public void showUnavailable() {
        statusDot.setText("");
        statusDot.setToolTipText(null);
        chips.removeAll();
        chips.add(placeholder("行情暂不可用"));
        refresh();
    }

    private JPanel createChip(Quote quote) {
        JPanel chip = Theme.transparentPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        JLabel name = new JLabel(quote.name());
        name.setFont(NAME_FONT);
        name.setForeground(Theme.MUTED);
        chip.add(name);

        JLabel percent = new JLabel(quote.percentText());
        percent.setFont(VALUE_FONT);
        percent.setForeground(changeColor(quote));
        chip.add(percent);

        String tip = quote.name() + "　" + quote.priceText() + "　" + quote.percentText();
        chip.setToolTipText(tip);
        name.setToolTipText(tip);
        percent.setToolTipText(tip);

        // 点击要能穿透到子标签上：鼠标落在文字上时，事件由标签消费，
        // 只给容器装监听会有一半区域点不动。
        makeClickable(chip);
        makeClickable(name);
        makeClickable(percent);
        return chip;
    }

    private JLabel placeholder(String text) {
        JLabel label = new JLabel(text);
        label.setFont(NAME_FONT);
        label.setForeground(Theme.MUTED);
        makeClickable(label);
        return label;
    }

    private static Color changeColor(Quote quote) {
        if (quote.up()) return Theme.QUOTE_UP;
        if (quote.down()) return Theme.QUOTE_DOWN;
        return Theme.QUOTE_FLAT;
    }

    private void makeClickable(Component component) {
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                listener.detailsRequested();
            }
        });
    }

    private void refresh() {
        chips.revalidate();
        chips.repaint();
        revalidate();
        repaint();
    }
}
