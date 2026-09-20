package com.calendar.ui;

import com.calendar.model.NewsItem;
import com.calendar.model.Quote;
import com.calendar.service.MarketClock;
import com.calendar.service.NewsService;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;

/**
 * 行情详情弹窗：指数 + 自选 + 要闻。
 *
 * <p>为什么这三块放在弹窗里而不是内联：挂件的高度是零和的，内联每多占 20px，
 * 日程列表就少显示一条。而"看行情详情"和"看今天要做什么"是两种不同的意图——
 * 后者需要常驻可见，前者是主动查看。把它们分层，两边都不用将就。
 *
 * <p>弹窗是<b>非模态</b>的：用户打开行情时可能想顺手改一下日程，
 * 模态弹窗会挡住主窗口，反而添乱。
 *
 * <p>要闻由弹窗自己异步加载，不由主窗口传入。这样"要闻多久刷新一次"这个问题
 * 就只归弹窗管——挂件常驻在桌面上，但弹窗只在用户主动打开时才存在，
 * 没必要让主窗口为了一个可能永远不被打开的功能定期发请求。
 */
public final class MarketDialog extends JDialog {

    /** 底部"管理自选"按钮的回调。 */
    public interface Listener {
        void manageWatchlistRequested();
    }

    private static final Font SECTION_FONT = Theme.FONT.deriveFont(Font.BOLD, 12f);
    private static final Font NAME_FONT = Theme.FONT.deriveFont(12f);
    private static final Font VALUE_FONT = Theme.FONT.deriveFont(Font.BOLD, 12f);
    private static final Font SMALL_FONT = Theme.FONT.deriveFont(11f);

    /** 要闻条数。再多会盖过行情本身，那才是这个弹窗的主角。 */
    private static final int NEWS_COUNT = 6;

    /** 同时只允许一个行情弹窗，避免反复点击开出好几个重叠的窗口。 */
    private static MarketDialog instance;

    private final JPanel newsSection = new JPanel();

    private MarketDialog(Window owner, List<Quote> indexes, List<Quote> watchlist, Listener listener) {
        super(owner, "行情", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 必须用宽度跟随视口的容器：要闻标题是 truncatingLabel，它的 preferredSize
        // 是全文宽度，用普通 JPanel 会把内容撑得比视口还宽，右侧的价格和涨跌幅就被裁掉了。
        ScrollableColumn content = new ScrollableColumn(14, 16, 14, 16);
        content.setBackground(Color.WHITE);

        content.add(statusLine());
        content.add(Box.createVerticalStrut(10));

        content.add(sectionHeader("指数", null));
        content.add(Box.createVerticalStrut(6));
        addQuoteRows(content, indexes);

        content.add(Box.createVerticalStrut(14));
        content.add(sectionHeader("自选", listener));
        content.add(Box.createVerticalStrut(6));
        if (watchlist.isEmpty()) {
            content.add(hint("还没有自选股。点右上角「管理自选」添加，"
                    + "或直接输入代码，如 600519、300750、AAPL。"));
        } else {
            addQuoteRows(content, watchlist);
        }

        newsSection.setOpaque(false);
        newsSection.setLayout(new BoxLayout(newsSection, BoxLayout.Y_AXIS));
        newsSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(14));
        content.add(newsSection);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setContentPane(scroll);

        setSize(430, 510);
        setLocationRelativeTo(owner);

        showNewsLoading();
        loadNewsAsync();
    }

    /** 打开弹窗。重复打开会替换掉上一个。 */
    public static void open(Window owner, List<Quote> indexes, List<Quote> watchlist, Listener listener) {
        if (instance != null) {
            instance.dispose();
        }
        instance = new MarketDialog(owner, indexes, watchlist, listener);
        instance.setVisible(true);
    }

    public static void closeIfOpen() {
        if (instance != null) {
            instance.dispose();
            instance = null;
        }
    }

    // ------------------------------------------------------------ 要闻

    private void showNewsLoading() {
        newsSection.removeAll();
        newsSection.add(sectionHeader("要闻", null));
        newsSection.add(Box.createVerticalStrut(6));
        JLabel loading = new JLabel("加载中…");
        loading.setFont(SMALL_FONT);
        loading.setForeground(Theme.MUTED);
        loading.setAlignmentX(Component.LEFT_ALIGNMENT);
        newsSection.add(loading);
        revalidate();
    }

    private void loadNewsAsync() {
        new SwingWorker<List<NewsItem>, Void>() {
            @Override protected List<NewsItem> doInBackground() {
                return NewsService.fetch(NEWS_COUNT);
            }

            @Override protected void done() {
                List<NewsItem> items;
                try {
                    items = get();
                } catch (Exception exception) {
                    items = List.of();
                }
                renderNews(items);
            }
        }.execute();
    }

    private void renderNews(List<NewsItem> items) {
        SwingUtilities.invokeLater(() -> {
            newsSection.removeAll();
            // 取不到要闻时整块消失，不留一个"暂无要闻"的空壳——
            // 那会让弹窗看起来像是坏了。
            if (items.isEmpty()) {
                revalidate();
                repaint();
                return;
            }
            newsSection.add(sectionHeader("要闻", null));
            newsSection.add(Box.createVerticalStrut(6));
            for (NewsItem item : items) {
                newsSection.add(newsRow(item));
                newsSection.add(Box.createVerticalStrut(5));
            }
            revalidate();
            repaint();
        });
    }

    // ------------------------------------------------------------ 组装

    private JComponent statusLine() {
        JLabel label = new JLabel(MarketClock.statusText());
        label.setFont(SMALL_FONT);
        label.setForeground(Theme.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JComponent sectionHeader(String title, Listener listener) {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel label = new JLabel(title);
        label.setFont(SECTION_FONT);
        label.setForeground(Theme.INK);
        header.add(label, BorderLayout.WEST);

        if (listener != null) {
            JButton manage = Theme.textButton("管理自选");
            manage.addActionListener(e -> {
                dispose();
                listener.manageWatchlistRequested();
            });
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(manage);
            header.add(right, BorderLayout.EAST);
        }
        return header;
    }

    private void addQuoteRows(JPanel content, List<Quote> quotes) {
        for (Quote quote : quotes) {
            content.add(quoteRow(quote));
            content.add(Box.createVerticalStrut(5));
        }
    }

    private JComponent quoteRow(Quote quote) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

        JLabel name = Theme.truncatingLabel(quote.name(), NAME_FONT, Theme.INK);
        name.setPreferredSize(new Dimension(110, 20));
        row.add(name, BorderLayout.WEST);

        JPanel values = new JPanel(new GridLayout(1, 2, 8, 0));
        values.setOpaque(false);
        values.setPreferredSize(new Dimension(180, 20));
        values.add(rightAligned(quote.priceText(), VALUE_FONT, Theme.INK));
        values.add(rightAligned(quote.percentText(), VALUE_FONT, changeColor(quote)));
        row.add(values, BorderLayout.EAST);

        row.setToolTipText(quote.name() + "　最新 " + quote.priceText()
                + "　涨跌 " + String.format("%+.2f", quote.change()) + "　" + quote.percentText());
        return row;
    }

    private JComponent newsRow(NewsItem item) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel time = new JLabel(item.time());
        time.setFont(SMALL_FONT);
        time.setForeground(Theme.MUTED);
        time.setPreferredSize(new Dimension(38, 18));
        row.add(time, BorderLayout.WEST);

        JLabel title = Theme.truncatingLabel(item.title(), SMALL_FONT, Theme.INK);
        row.add(title, BorderLayout.CENTER);

        if (!item.sourceText().isEmpty()) {
            JLabel source = new JLabel(item.sourceText());
            source.setFont(SMALL_FONT);
            source.setForeground(Theme.MUTED);
            row.add(source, BorderLayout.EAST);
        }
        row.setToolTipText(item.title());
        return row;
    }

    private JLabel hint(String text) {
        JLabel label = new JLabel("<html><div style='width:340px'>" + text + "</div></html>");
        label.setFont(SMALL_FONT);
        label.setForeground(Theme.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel rightAligned(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        label.setHorizontalAlignment(JLabel.RIGHT);
        return label;
    }

    private static Color changeColor(Quote quote) {
        if (quote.up()) return Theme.QUOTE_UP;
        if (quote.down()) return Theme.QUOTE_DOWN;
        return Theme.QUOTE_FLAT;
    }
}
