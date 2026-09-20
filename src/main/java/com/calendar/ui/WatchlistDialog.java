package com.calendar.ui;

import com.calendar.model.Quote;
import com.calendar.service.QuoteService;
import com.calendar.service.WatchlistStore;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * 自选股管理。
 *
 * <p>只做一件事：把用户想关注的标的加进来、移出去。刻意不做"搜索选股"——
 * 那需要额外接一个搜索接口，而输入框已经支持三种写法（{@code 600519}、
 * {@code sh600519}、{@code AAPL}），覆盖了绝大多数场景，多接一个接口
 * 就多一份会失效的依赖。
 *
 * <p>添加时会<b>真的去拉一次行情</b>来验证代码。这一步不能省：代码写错时
 * 行情接口不会报错，只会返回空——用户加了之后行情条上什么都不显示，
 * 完全不知道是自己写错了还是软件坏了。多花一次 200ms 的请求，
 * 换掉一整类说不清的困惑。
 *
 * <p>失败提示必须<b>说清是哪一种失败</b>，这是本类最容易写错的地方。
 * 三种情况要给三种话：格式不对（本地就能判定，说出错在哪位）、
 * 代码不存在（接口返回空条目）、请求被挡下或网络不通。曾经把前两种
 * 一并说成"网络不可用"，用户就会去反复检查网络，而真正的原因是他少打了一位数字。
 */
public final class WatchlistDialog extends JDialog {

    /** 自选发生变化时通知主窗口刷新。 */
    public interface Listener {
        void changed();
    }

    private static final Font NAME_FONT = Theme.FONT.deriveFont(Font.BOLD, 12f);
    private static final Font CODE_FONT = Theme.FONT.deriveFont(11f);
    private static final Font HINT_FONT = Theme.FONT.deriveFont(11f);

    private final WatchlistStore store;
    private final Listener listener;
    private final JTextField input = new JTextField();
    private final JButton addButton = new JButton("添加");
    private final JLabel feedback = new JLabel(" ");
    private final JPanel list = new JPanel();
    private final Map<String, String> names = new HashMap<>();

    private WatchlistDialog(Window owner, WatchlistStore store, Listener listener) {
        super(owner, "管理自选股", ModalityType.APPLICATION_MODAL);
        this.store = store;
        this.listener = listener;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(14, 16, 14, 16));

        content.add(createInputArea(), BorderLayout.NORTH);
        content.add(createListArea(), BorderLayout.CENTER);
        content.add(createFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        setSize(410, 430);
        setLocationRelativeTo(owner);
        refreshList();
        loadNames();
    }

    public static void open(Window owner, WatchlistStore store, Listener listener) {
        new WatchlistDialog(owner, store, listener).setVisible(true);
    }

    // ------------------------------------------------------------ 组装

    private JComponent createInputArea() {
        JPanel area = new JPanel();
        area.setOpaque(false);
        area.setLayout(new BoxLayout(area, BoxLayout.Y_AXIS));

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        input.setFont(Theme.FONT.deriveFont(12f));
        input.putClientProperty("JTextField.placeholderText", "输入代码，如 600519 / AAPL");
        input.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                    submit();
                }
            }
        });
        row.add(input, BorderLayout.CENTER);

        addButton.setFont(Theme.FONT.deriveFont(Font.BOLD, 12f));
        addButton.setForeground(Color.WHITE);
        addButton.setFocusable(false);
        Theme.stylePrimaryButton(addButton);
        addButton.addActionListener(e -> submit());
        row.add(addButton, BorderLayout.EAST);
        area.add(row);

        area.add(Box.createVerticalStrut(6));
        feedback.setFont(HINT_FONT);
        feedback.setForeground(Theme.MUTED);
        feedback.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.add(feedback);
        return area;
    }

    private JComponent createListArea() {
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private JComponent createFooter() {
        // 用 HTML 换行而不是让它在窄窗口里被截断——这段提示是用户唯一能查到
        // "代码该怎么写"的地方，被切掉一半就等于没有。
        JLabel hint = new JLabel("<html>支持沪市 6 开头、深市 0/3 开头、北交所 4/8 开头，<br>"
                + "以及美股代码（如 AAPL）。最多 " + WatchlistStore.MAX_ITEMS + " 个。</html>");
        hint.setFont(HINT_FONT);
        hint.setForeground(Theme.MUTED);
        return hint;
    }

    // ------------------------------------------------------------ 行为

    private void submit() {
        String raw = input.getText().trim();
        if (raw.isEmpty()) return;

        // 先做本地格式校验，不发请求。格式错误和"代码不存在"是两件事，
        // 前者本地就能判定，没必要花一次网络往返、更没必要让它显示成网络问题。
        String code = QuoteService.normalize(raw);
        if (code.isEmpty()) {
            warn(QuoteService.formatHint(raw));
            return;
        }
        if (store.contains(code)) {
            warn("已经在自选里了");
            return;
        }
        if (store.isFull()) {
            warn("最多只能添加 " + WatchlistStore.MAX_ITEMS + " 个");
            return;
        }

        setBusy(true, "正在校验 " + code + " …");
        new SwingWorker<List<Quote>, Void>() {
            @Override protected List<Quote> doInBackground() throws Exception {
                return QuoteService.fetch(List.of(code));
            }

            @Override protected void done() {
                try {
                    List<Quote> quotes = get();
                    if (quotes.isEmpty()) {
                        setBusy(false, "");
                        warn("没找到 " + code + "，请确认代码是否正确");
                        return;
                    }
                    Quote quote = quotes.get(0);
                    names.put(code, quote.name());
                    store.add(code);
                    input.setText("");
                    setBusy(false, "");
                    info("已添加 " + quote.name());
                    refreshList();
                    listener.changed();
                } catch (Exception error) {
                    setBusy(false, "");
                    // SwingWorker.get() 只声明 InterruptedException / ExecutionException，
                    // 后台抛的异常被包在 ExecutionException 里，要拆一层才能认出。
                    Throwable cause = error instanceof ExecutionException
                            ? error.getCause() : error;
                    if (cause instanceof QuoteService.NotFoundException) {
                        // 数据源应答正常，只是没这只标的——用户的代码写错了。
                        warn("没找到 " + code + "，请确认代码是否正确");
                    } else if (cause instanceof QuoteService.RejectedException) {
                        // 请求送到了但被挡下来，与网络不通不是一回事。
                        warn("行情源拒绝了这次查询，请稍后再试");
                    } else {
                        warn("网络不可用，暂时无法校验代码");
                    }
                }
            }
        }.execute();
    }

    private void remove(String code) {
        store.remove(code);
        names.remove(code);
        info("已移除 " + code);
        refreshList();
        listener.changed();
    }

    private void refreshList() {
        list.removeAll();
        List<String> codes = store.load();
        if (codes.isEmpty()) {
            JLabel empty = new JLabel("还没有自选股");
            empty.setFont(HINT_FONT);
            empty.setForeground(Theme.MUTED);
            empty.setBorder(new EmptyBorder(10, 2, 0, 0));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(empty);
        } else {
            for (String code : codes) {
                list.add(createRow(code));
                list.add(Box.createVerticalStrut(6));
            }
        }
        list.revalidate();
        list.repaint();
    }

    private JComponent createRow(String code) {
        RoundedPanel row = new RoundedPanel(10, Theme.GROUPED_BACKGROUND);
        row.setLayout(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setBorder(new EmptyBorder(6, 10, 6, 8));

        JPanel copy = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        copy.setOpaque(false);
        JLabel name = new JLabel(names.getOrDefault(code, "—"));
        name.setFont(NAME_FONT);
        name.setForeground(Theme.INK);
        copy.add(name);
        JLabel codeLabel = new JLabel(code);
        codeLabel.setFont(CODE_FONT);
        codeLabel.setForeground(Theme.MUTED);
        copy.add(codeLabel);
        row.add(copy, BorderLayout.CENTER);

        JButton remove = Theme.textButton("移除");
        remove.setForeground(Theme.DANGER);
        remove.addActionListener(e -> remove(code));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(remove);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** 后台补全显示名。拿不到就只显示代码，不阻塞界面。 */
    private void loadNames() {
        List<String> codes = store.load();
        if (codes.isEmpty()) return;
        new SwingWorker<List<Quote>, Void>() {
            @Override protected List<Quote> doInBackground() throws Exception {
                return QuoteService.fetch(codes);
            }

            @Override protected void done() {
                try {
                    for (Quote quote : get()) {
                        names.put(quote.code(), quote.name());
                    }
                    SwingUtilities.invokeLater(WatchlistDialog.this::refreshList);
                } catch (Exception ignored) {
                    // 名称只是锦上添花，取不到就保持显示代码。
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy, String message) {
        addButton.setEnabled(!busy);
        input.setEnabled(!busy);
        if (!message.isEmpty()) {
            feedback.setForeground(Theme.MUTED);
            feedback.setText(message);
        }
    }

    private void warn(String message) {
        feedback.setForeground(Theme.DANGER);
        feedback.setText(message);
    }

    private void info(String message) {
        feedback.setForeground(Theme.QUOTE_DOWN);
        feedback.setText(message);
    }
}
