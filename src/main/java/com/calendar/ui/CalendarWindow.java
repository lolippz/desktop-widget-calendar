package com.calendar.ui;

import com.calendar.data.AppPaths;
import com.calendar.data.ScheduleStore;
import com.calendar.model.DayMeta;
import com.calendar.model.HistoryEvent;
import com.calendar.model.Quote;
import com.calendar.model.Schedule;
import com.calendar.service.HistoryService;
import com.calendar.service.LunarService;
import com.calendar.service.MarketClock;
import com.calendar.service.QuoteService;
import com.calendar.service.ReminderService;
import com.calendar.service.WatchlistStore;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * 无边框桌面日程卡片主窗口。
 *
 * <p>职责是"编排"：持有选中日期与显示月份，把数据层和各个面板串起来，
 * 并托管系统托盘。具体渲染在 {@link CalendarGrid} / {@link AgendaPanel}，
 * 编辑在 {@link ScheduleEditor}，农历在 {@link LunarService}。
 */
public final class CalendarWindow extends JFrame {

    /*
     * 首选项键名全部收在这里。散落的字符串字面量一旦打错，症状是"设置不生效"，
     * 而且不会报错——很难查，所以不给它打错的机会。
     */
    private static final String KEY_ALWAYS_ON_TOP = "alwaysOnTop";
    private static final String KEY_MONTH_MODE = "monthMode";
    private static final String KEY_OPACITY_PERCENT = "opacityPercent";
    private static final String KEY_SNAP_ENABLED = "snapEnabled";
    private static final String KEY_WINDOW_X = "windowX";
    private static final String KEY_WINDOW_Y = "windowY";
    private static final String KEY_WINDOW_WIDTH = "windowWidth";
    private static final String KEY_WINDOW_HEIGHT = "windowHeight";
    private static final String KEY_SHOW_QUOTES = "showQuotes";
    private static final String KEY_SHOW_HISTORY = "showHistory";

    private final LocalDate today = LocalDate.now();
    private LocalDate selectedDate = today;
    private YearMonth displayedMonth = YearMonth.from(today);

    private final ScheduleStore store;
    private final ReminderService reminders;
    private final CalendarGrid calendarGrid;
    private final AgendaPanel agendaPanel;
    private final TodayCard todayCard = new TodayCard();
    private final JLabel monthLabel = new JLabel();

    private JButton modeButton;
    private JButton previousMonthButton;
    private JButton nextMonthButton;

    /** true = 月历模式（看整月），false = 今日模式（看现在）。挂件默认后者。 */
    private boolean monthMode;

    private TrayIcon trayIcon;
    private CheckboxMenuItem alwaysOnTopItem;
    private boolean trayHintShown;
    private final boolean translucencySupported;

    /** 窗口级不透明度是否可用。不可用时相关菜单项置灰。 */
    private final boolean opacitySupported;
    private int opacityPercent;
    private boolean snapEnabled;
    private boolean syncingTrayOpacity;
    private final Map<Integer, CheckboxMenuItem> trayOpacityItems = new HashMap<>();

    /** 当前窗口尺寸，拖动调整后持久化。 */
    private int windowWidth;
    private int windowHeight;

    /** 正在进行的调整大小操作；{@link WindowGeometry#ZONE_NONE} 表示没有。 */
    private int resizeZone = WindowGeometry.ZONE_NONE;
    private Rectangle resizeStartBounds;
    private Point resizeStartOnScreen;
    /** 全局鼠标移动监听，只用来刷新边缘光标。见 {@link #installResizeCursorDispatcher()}。 */
    private AWTEventListener resizeCursorDispatcher;
    /** 已生效的边缘光标，避免每次鼠标移动都重复 setCursor 触发重绘。 */
    private int appliedResizeCursor = -1;

    /*
     * 行情与"历史上的今天"。两者都是可选内容，且都只在今日模式下出现——
     * 月历模式的正文被 6 行日期网格占满，再加东西就会把议程列表挤没。
     */
    private final QuoteTicker quoteTicker;
    private final HistoryStrip historyStrip;
    private final WatchlistStore watchlistStore = new WatchlistStore();
    /** 行情条的容器。连同上方的间距一起显隐，避免隐藏后留下一道空隙。 */
    private final JPanel quoteSlot = Theme.transparentPanel(new BorderLayout());
    /** 今日卡片 + 行情条的纵向块。月历模式下整块隐藏，而不是留一个 0 高度的空壳。 */
    private JPanel todayBlock;

    private boolean showQuotes;
    private boolean showHistory;

    /** 行情定时刷新。间隔随交易时段变化，见 {@link MarketClock#refreshSeconds()}。 */
    private Timer quoteTimer;
    /** 上一轮请求还没回来时不再发起新的，避免慢网络下请求越堆越多。 */
    private boolean quoteLoading;

    /**
     * 最近一次成功获取的行情，供弹窗直接复用。
     *
     * <p>不复用的话，每次打开弹窗都得等一次网络往返，用户会先看到一个空白窗口。
     * 要闻则不在主窗口缓存——它只在弹窗里出现，由弹窗自己按需加载。
     */
    private List<Quote> latestQuotes = List.of();

    /**
     * 首选项节点写死，不用 {@code userNodeForPackage}——后者会随包名变化，
     * 重构后用户已保存的窗口位置会凭空丢失。详见 {@link AppPaths#PREFERENCES_PATH}。
     */
    private final Preferences preferences =
            Preferences.userRoot().node(AppPaths.PREFERENCES_PATH);

    public CalendarWindow() {
        super(AppPaths.APP_NAME);
        store = new ScheduleStore(this::showDatabaseError);
        store.createSchema();

        setUndecorated(true);
        // A desktop widget belongs in the tray after minimising, not in the Windows taskbar.
        setType(Window.Type.UTILITY);
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        translucencySupported = device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        // 窗口级不透明度是另一项能力，与逐像素透明不互为前提，必须分别探测。
        opacitySupported = device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        setBackground(translucencySupported ? new Color(0, 0, 0, 0) : Color.WHITE);
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        // 偏好要提前读：尺寸下限随视图模式、以及附加内容的开关变化，恢复尺寸时就要用到。
        monthMode = preferences.getBoolean(KEY_MONTH_MODE, false);
        opacityPercent = preferences.getInt(KEY_OPACITY_PERCENT, Theme.DEFAULT_OPACITY);
        snapEnabled = preferences.getBoolean(KEY_SNAP_ENABLED, true);
        showQuotes = preferences.getBoolean(KEY_SHOW_QUOTES, true);
        showHistory = preferences.getBoolean(KEY_SHOW_HISTORY, true);
        setAlwaysOnTop(preferences.getBoolean(KEY_ALWAYS_ON_TOP, true));
        setMinimumSize(minimumWindowSize());
        // 尺寸必须先恢复：位置越界检查要用真实尺寸，"窗口右半在屏幕外"用默认常量算会被误判成在屏幕内。
        restoreWindowGeometry();
        installResizeCursorDispatcher();
        addWindowStateListener(event -> {
            if ((event.getNewState() & Frame.ICONIFIED) != 0) hideToTray();
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { saveWindowState(); }
        });

        calendarGrid = new CalendarGrid(LunarService::describe, new CalendarGrid.Listener() {
            @Override public void dateClicked(LocalDate date, int clickCount) {
                selectedDate = date;
                if (clickCount == 2) openEditor(null, date);
                refreshAll();
            }

            @Override public void monthShiftRequested(int delta) {
                shiftMonth(delta);
            }
        });
        agendaPanel = new AgendaPanel(new AgendaPanel.Listener() {
            @Override public void addRequested() {
                openEditor(null, selectedDate);
            }

            @Override public void scheduleOpened(Schedule schedule) {
                openEditor(schedule, schedule.date());
            }
        });

        quoteTicker = new QuoteTicker(this::openMarketDialog);
        historyStrip = new HistoryStrip(this::openHistoryDialog);
        // 间距挂在容器上而不是直接加在行情条上：隐藏时容器一起消失，
        // 否则会留下一道 10px 的空白，看起来像布局坏了。
        quoteSlot.setBorder(new EmptyBorder(10, 0, 0, 0));
        quoteSlot.add(quoteTicker, BorderLayout.CENTER);

        setContentPane(createContent());
        applyMode();
        applyOpacity();
        refreshAll();
        installTrayIcon();
        checkHolidayCoverage();
        startQuoteRefresh();

        reminders = new ReminderService(store, this::showTrayMessage);
        reminders.start();
    }

    // ---------------------------------------------------------------- 界面

    private JComponent createContent() {
        RoundedPanel card = new RoundedPanel(24, translucencySupported
                ? new Color(255, 255, 255, 248) : Color.WHITE);
        card.setBorder(new EmptyBorder(16, 18, 16, 18));
        card.setLayout(new BorderLayout(0, 10));
        card.add(createHeader(), BorderLayout.NORTH);

        // 今日卡片与月历网格互斥：同时显示时，月历会吃掉议程列表所需的高度。
        JPanel body = Theme.transparentPanel(new BorderLayout(0, 12));
        body.add(createTodayBlock(), BorderLayout.NORTH);
        body.add(createAgendaBlock(), BorderLayout.CENTER);
        body.add(calendarGrid, BorderLayout.SOUTH);
        card.add(body, BorderLayout.CENTER);
        // 无边框窗口看不出能拉伸，给右下角一个视觉暗示。
        card.setCornerGripVisible(true);
        // 顺序有意义：调整大小要先于移动安装，同一组件上的监听器按添加顺序触发，
        // 边缘按下时它先抢下本次交互，后面的移动逻辑才不会把窗口一边拖走一边缩放。
        installResizeHandle(card);
        installDrag(card);
        installContextMenu(card);
        return card;
    }

    /**
     * 今日卡片 + 行情条。
     *
     * <p>两者都是"今天"的信息，收在同一个纵向块里，切到月历模式时可以整块隐藏，
     * 不必分别处理它们之间的间距。
     */
    private JComponent createTodayBlock() {
        JPanel block = Theme.transparentPanel(null);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        todayCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        quoteSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(todayCard);
        block.add(quoteSlot);
        todayBlock = block;
        return block;
    }

    /**
     * 议程列表 + 历史上的今天入口。
     *
     * <p>历史入口放在 SOUTH（滚动区之外）而不是跟在列表末尾：议程列表本身是可滚动的，
     * 若排在滚动区内部，用户得先滚到底才能看到它——而它恰恰是"没有日程时"最有价值的内容。
     */
    private JComponent createAgendaBlock() {
        JPanel block = Theme.transparentPanel(new BorderLayout(0, 10));
        block.add(agendaPanel, BorderLayout.CENTER);
        block.add(historyStrip, BorderLayout.SOUTH);
        return block;
    }

    private JComponent createHeader() {
        JPanel header = Theme.transparentPanel(new BorderLayout());

        JPanel navigation = Theme.transparentPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        modeButton = Theme.toggleButton("月历");
        modeButton.addActionListener(e -> toggleMode());
        previousMonthButton = Theme.iconButton("‹", "上一个月");
        nextMonthButton = Theme.iconButton("›", "下一个月");
        previousMonthButton.addActionListener(e -> shiftMonth(-1));
        nextMonthButton.addActionListener(e -> shiftMonth(1));
        monthLabel.setFont(Theme.FONT.deriveFont(Font.BOLD, 17));
        monthLabel.setForeground(Theme.INK);
        monthLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        monthLabel.setToolTipText("回到今天");
        monthLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { goToday(); }
        });
        navigation.add(modeButton);
        navigation.add(previousMonthButton);
        navigation.add(monthLabel);
        navigation.add(nextMonthButton);
        header.add(navigation, BorderLayout.WEST);

        JPanel actions = Theme.transparentPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton goToday = Theme.textButton("今天");
        goToday.addActionListener(e -> goToday());
        // 右键菜单太隐蔽：挂件的外观设置需要一个看得见的入口，否则用户根本不知道有这功能。
        // 用 U+2026 而不是 U+22EF：后者在 Microsoft YaHei UI 里缺字，会渲染成方块。
        JButton more = Theme.iconButton("…", "更多设置（透明度 / 贴边吸附 / 重置大小）");
        more.setFont(Theme.FONT.deriveFont(Font.BOLD, 20f));
        more.addActionListener(e -> createContextMenu().show(more, 0, more.getHeight()));
        JButton close = Theme.iconButton("×", "隐藏到系统托盘");
        close.addActionListener(e -> hideToTray());
        actions.add(goToday);
        actions.add(more);
        actions.add(close);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    /** 在"今日优先"与"月历"两种视图之间切换。 */
    private void toggleMode() {
        setMonthMode(!monthMode);
    }

    /**
     * 设置视图模式并持久化。
     *
     * <p>挂件的密度偏好因人而异，记住用户的选择比替他决定更合适。
     */
    public void setMonthMode(boolean monthMode) {
        this.monthMode = monthMode;
        preferences.putBoolean(KEY_MONTH_MODE, monthMode);
        applyMode();
        refreshAll();
    }

    private void applyMode() {
        todayCard.setVisible(!monthMode);
        // 整块隐藏，而不是只隐藏里面的子组件：留一个"可见但 0 高度"的容器
        // 会让布局自检工具（以及日后读代码的人）分不清"这里本该没有内容"
        // 和"这里的内容被挤没了"。
        todayBlock.setVisible(!monthMode);
        calendarGrid.setVisible(monthMode);
        // 今日模式下农历信息已在今日卡片上，摘要条会重复，隐藏它以让出高度给日程列表。
        agendaPanel.setDetailBarVisible(monthMode);
        // 月份前后翻页只对月历视图有意义。
        previousMonthButton.setVisible(monthMode);
        nextMonthButton.setVisible(monthMode);
        modeButton.setText(monthMode ? "今日" : "月历");
        modeButton.setToolTipText(monthMode ? "切换到今日视图" : "切换到月历视图");
        historyStrip.setAllowed(showHistory && !monthMode);
        applyQuoteVisibility();
        applyMinimumSize();
    }

    /**
     * 行情条只在今日模式、且用户开着它的时候出现。
     *
     * <p>月历模式下正文已经被 6 行日期网格占满，再插一行行情就会把议程列表挤没。
     */
    private void applyQuoteVisibility() {
        quoteSlot.setVisible(showQuotes && !monthMode);
        revalidate();
    }

    /**
     * 当前视图模式下的窗口尺寸下限。
     *
     * <p>今日模式下还要加上附加内容的高度。行情条与历史入口都是常驻区块，
     * 它们吃掉的高度必须从"最小尺寸"里补回来——否则把窗口缩到最小时，
     * 议程列表会被压到只剩一条，"看今天要做什么"这个核心功能反而最先被牺牲。
     *
     * <p>月历模式下两个区块都不显示，直接用基础下限。
     */
    private Dimension minimumWindowSize() {
        Dimension base = Theme.minimumWindowSize(monthMode);
        if (monthMode) return base;
        int extra = 0;
        if (showQuotes) extra += Theme.QUOTE_BLOCK_HEIGHT;
        if (showHistory) extra += Theme.HISTORY_BLOCK_HEIGHT;
        return new Dimension(base.width, base.height + extra);
    }

    /**
     * 重算尺寸下限，必要时把窗口抬到下限。
     *
     * <p>视图模式或附加内容开关变化后都要走一次：下限是这两个变量的函数，
     * 变了却不重新应用，就会出现"最小尺寸与实际内容不匹配"的状态。
     */
    private void applyMinimumSize() {
        setMinimumSize(minimumWindowSize());
        growToMinimumSizeIfNeeded();
    }

    /**
     * 切到月历模式时，窗口可能比该模式的下限还小，就地长大。
     *
     * <p>不这么做的话：固定 6 行的日期网格会把议程列表挤成一条 4px 的缝，
     * 标题栏的翻月箭头也会和右侧按钮叠在一起——看起来就像界面坏了。
     * 只长不缩：用户自己拉大的尺寸不该因为切了视图就被改回去。
     */
    private void growToMinimumSizeIfNeeded() {
        Dimension minimum = minimumWindowSize();
        if (getWidth() >= minimum.width && getHeight() >= minimum.height) return;
        Rectangle grown = new Rectangle(getX(), getY(), Math.max(getWidth(), minimum.width),
                Math.max(getHeight(), minimum.height));
        // 长大可能把右下边缘顶到任务栏底下，平移回可用区域内（尺寸是刚算好的，只能挪位置）。
        setBounds(WindowGeometry.keepInside(grown, usableScreenBounds()));
        saveWindowState();
    }

    private void shiftMonth(int delta) {
        if (delta == 0) return;
        displayedMonth = displayedMonth.plusMonths(delta);
        // 让选中日期跟随显示月份，避免"网格里没有选中框，下方却显示旧日期日程"。
        if (!YearMonth.from(selectedDate).equals(displayedMonth)) {
            selectedDate = displayedMonth.atDay(Math.min(selectedDate.getDayOfMonth(),
                    displayedMonth.lengthOfMonth()));
        }
        refreshAll();
    }

    private void goToday() {
        selectedDate = today;
        displayedMonth = YearMonth.from(today);
        refreshAll();
    }

    // ---------------------------------------------------------------- 刷新

    private void refreshAll() {
        refreshCalendar();
        refreshAgenda();
    }

    private void refreshCalendar() {
        monthLabel.setText(displayedMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.CHINA)
                + " " + displayedMonth.getYear());
        // 整月只查一次库，再在内存里按天展开重复规则。
        List<Schedule> monthSchedules = store.loadSchedulesInRange(displayedMonth.atDay(1),
                displayedMonth.atEndOfMonth());
        Set<LocalDate> scheduledDates = new HashSet<>();
        for (int day = 1; day <= displayedMonth.lengthOfMonth(); day++) {
            if (!ScheduleStore.schedulesOn(monthSchedules, displayedMonth.atDay(day)).isEmpty()) {
                scheduledDates.add(displayedMonth.atDay(day));
            }
        }
        calendarGrid.render(displayedMonth, today, selectedDate, scheduledDates);
    }

    private void refreshAgenda() {
        DayMeta dayMeta = LunarService.describe(selectedDate);
        String countdown = LunarService.holidayCountdown(selectedDate);
        todayCard.render(selectedDate, dayMeta, countdown);

        // 顺序即优先级：摘要条放不下时会从尾部截断，所以干支这种最不"扫一眼"的信息排最后。
        StringBuilder detail = new StringBuilder("农历 ").append(dayMeta.fullLunar())
                .append("　").append(dayMeta.detailText());
        if (!countdown.isBlank()) {
            detail.append("　").append(countdown);
        }
        detail.append("　").append(dayMeta.ganZhi());
        if (dayMeta.holidayDataMissing()) {
            detail.append("　· 该年节假日数据缺失");
        }

        List<Schedule> schedules = new ArrayList<>(ScheduleStore.schedulesOn(
                store.loadSchedulesInRange(selectedDate, selectedDate), selectedDate));
        schedules.sort(Comparator.comparing(Schedule::sortKey));
        agendaPanel.render(selectedDate, detail.toString(), schedules);
        refreshHistory();
    }

    // ---------------------------------------------------------------- 行情与历史

    /**
     * 启动行情定时刷新。
     *
     * <p>首次延迟取 400ms 而不是一个刷新周期：界面这时候已经画出来了，
     * 用户看到的是"行情加载中"，没必要让他等满一分钟才看到数字。
     */
    private void startQuoteRefresh() {
        quoteTicker.showLoading();
        quoteTimer = new Timer(MarketClock.refreshSeconds() * 1000, e -> refreshQuotes());
        quoteTimer.setInitialDelay(400);
        quoteTimer.start();
    }

    /**
     * 拉取行情并更新行情条。
     *
     * <p>三个前置条件都必要：用户关掉了行情条就不该发请求；窗口隐藏到托盘时
     * 用户看不到数字，请求纯属浪费；上一轮还在飞的时候再发一个，
     * 在慢网络下会让请求越堆越多，最后一起超时。
     */
    private void refreshQuotes() {
        if (!showQuotes || !isVisible() || quoteLoading) return;

        List<String> codes = new ArrayList<>(QuoteService.DEFAULT_INDEX_CODES);
        codes.addAll(watchlistStore.load());
        quoteLoading = true;

        new SwingWorker<List<Quote>, Void>() {
            @Override protected List<Quote> doInBackground() throws Exception {
                return QuoteService.fetch(codes);
            }

            @Override protected void done() {
                quoteLoading = false;
                try {
                    List<Quote> quotes = get();
                    if (quotes.isEmpty()) {
                        quoteTicker.showUnavailable();
                    } else {
                        latestQuotes = quotes;
                        quoteTicker.showQuotes(quotes);
                    }
                } catch (Exception exception) {
                    // 包括 IOException 与中断。取不到就把这一条标成不可用，
                    // 不清空已有内容——下一次刷新成功时会覆盖回来。
                    quoteTicker.showUnavailable();
                }
                // 刷新间隔随交易时段变化：开盘时一分钟，休市时半小时。
                if (quoteTimer != null) {
                    quoteTimer.setDelay(MarketClock.refreshSeconds() * 1000);
                }
            }
        }.execute();
    }

    /**
     * 刷新"历史上的今天"。
     *
     * <p>只对当天有意义：上游接口没有日期参数，选中别的日期时直接清空，
     * 让这个区块隐藏、把高度让给日程列表。
     */
    private void refreshHistory() {
        if (!showHistory) {
            historyStrip.render(List.of());
            return;
        }
        LocalDate date = selectedDate;
        new SwingWorker<List<HistoryEvent>, Void>() {
            @Override protected List<HistoryEvent> doInBackground() {
                return HistoryService.forDate(date);
            }

            @Override protected void done() {
                try {
                    historyStrip.render(get());
                } catch (Exception exception) {
                    historyStrip.render(List.of());
                }
            }
        }.execute();
    }

    /** 打开行情详情弹窗。指数与自选分开传，弹窗里分成两段显示。 */
    public void openMarketDialog() {
        List<Quote> indexes = new ArrayList<>();
        List<Quote> watches = new ArrayList<>();
        List<String> watchCodes = watchlistStore.load();
        for (Quote quote : latestQuotes) {
            if (QuoteService.DEFAULT_INDEX_CODES.contains(quote.code())) {
                indexes.add(quote);
            } else if (watchCodes.contains(quote.code())) {
                watches.add(quote);
            }
        }
        MarketDialog.open(this, indexes, watches, this::openWatchlistDialog);
    }

    /** 打开自选股管理。列表变化后行情条需要立刻重画，否则用户会觉得没生效。 */
    public void openWatchlistDialog() {
        WatchlistDialog.open(this, watchlistStore, () -> {
            refreshQuotes();
            MarketDialog.closeIfOpen();
        });
    }

    public void openHistoryDialog(List<HistoryEvent> events) {
        HistoryDialog.open(this, events);
    }

    /** 右键菜单里的"显示行情条"开关。 */
    public void setShowQuotes(boolean enabled) {
        showQuotes = enabled;
        preferences.putBoolean(KEY_SHOW_QUOTES, enabled);
        applyQuoteVisibility();
        // 开关影响尺寸下限：关掉之后用户可以把窗口缩得更小。
        applyMinimumSize();
        if (enabled) {
            refreshQuotes();
        }
    }

    /** 右键菜单里的"显示历史上的今天"开关。 */
    public void setShowHistory(boolean enabled) {
        showHistory = enabled;
        preferences.putBoolean(KEY_SHOW_HISTORY, enabled);
        historyStrip.setAllowed(enabled && !monthMode);
        applyMinimumSize();
        refreshHistory();
        revalidate();
    }

    private void openEditor(Schedule existing, LocalDate date) {
        ScheduleEditor.open(this, existing, date, store, this::refreshAll);
    }

    private void showDatabaseError(SQLException exception) {
        JOptionPane.showMessageDialog(this, "读取或保存日程失败：" + exception.getMessage(),
                "数据库错误", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- 提醒与自检

    private void showTrayMessage(String title, String body) {
        if (trayIcon == null) return;
        trayIcon.displayMessage(title, body, TrayIcon.MessageType.INFO);
    }

    /** lunar 的假日数据按年硬编码，提前一年提醒用户升级依赖，避免跨年后静默失效。 */
    private void checkHolidayCoverage() {
        if (LunarService.isNextYearCovered() || trayIcon == null) return;
        trayIcon.displayMessage(AppPaths.APP_NAME,
                (LocalDate.now().getYear() + 1) + " 年的法定节假日数据尚未覆盖，休/班标注与假期倒计时将不可用，"
                        + "请升级 lunar 依赖到最新版本。",
                TrayIcon.MessageType.WARNING);
    }

    // ---------------------------------------------------------------- 托盘与窗口

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        menu.setFont(Theme.NATIVE_MENU_FONT);
        // Unicode escapes avoid source/locale conversion issues in the native AWT tray menu.
        MenuItem show = trayMenuItem("\u663e\u793a\u65e5\u7a0b");
        MenuItem hide = trayMenuItem("\u9690\u85cf\u65e5\u7a0b");
        MenuItem add = trayMenuItem("\u65b0\u5efa\u65e5\u7a0b");
        alwaysOnTopItem = new CheckboxMenuItem("\u603b\u5728\u6700\u524d", isAlwaysOnTop());
        MenuItem exit = trayMenuItem("\u9000\u51fa");
        show.addActionListener(e -> showWindow());
        hide.addActionListener(e -> hideToTray());
        add.addActionListener(e -> {
            showWindow();
            openEditor(null, selectedDate);
        });
        alwaysOnTopItem.addItemListener(e -> {
            boolean on = alwaysOnTopItem.getState();
            setAlwaysOnTop(on);
            preferences.putBoolean(KEY_ALWAYS_ON_TOP, on);
        });
        exit.addActionListener(e -> exitApplication());

        // 挂件常驻托盘，透明度这类外观设置放这里比藏在右键菜单里更容易被发现。
        Menu opacityMenu = new Menu("\u900f\u660e\u5ea6");
        opacityMenu.setFont(Theme.NATIVE_MENU_FONT);
        for (int step : Theme.OPACITY_STEPS) {
            CheckboxMenuItem item = new CheckboxMenuItem(step + "%", step == opacityPercent);
            item.setFont(Theme.NATIVE_MENU_FONT);
            item.setEnabled(opacitySupported);
            item.addItemListener(e -> {
                if (syncingTrayOpacity || !item.getState()) return;
                setOpacityPercent(step);
            });
            trayOpacityItems.put(step, item);
            opacityMenu.add(item);
        }

        menu.add(show);
        menu.add(hide);
        menu.add(add);
        menu.addSeparator();
        menu.add(alwaysOnTopItem);
        menu.add(opacityMenu);
        menu.addSeparator();
        menu.add(exit);
        trayIcon = new TrayIcon(createTrayImage(), AppPaths.APP_NAME, menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showWindow());
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException exception) {
            trayIcon = null;
        }
    }

    private static Image createTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Theme.BLUE);
        graphics.fillRoundRect(1, 1, 14, 14, 5, 5);
        graphics.setColor(Color.WHITE);
        graphics.drawLine(4, 6, 12, 6);
        graphics.drawLine(4, 9, 10, 9);
        graphics.dispose();
        return image;
    }

    private void hideToTray() {
        setVisible(false);
        saveWindowState();
        // 只在首次隐藏时提示，避免每次关闭都被打扰。
        if (trayIcon != null && !trayHintShown) {
            trayHintShown = true;
            trayIcon.displayMessage(AppPaths.APP_NAME, "应用仍在系统托盘中运行，点击图标可重新打开。",
                    TrayIcon.MessageType.NONE);
        }
    }

    private void showWindow() {
        setVisible(true);
        setState(Frame.NORMAL);
        toFront();
        requestFocus();
    }

    /** 停掉后台提醒调度、行情轮询并移除托盘图标。退出菜单与测试清理都走这里。 */
    public void shutdown() {
        reminders.stop();
        stopQuoteRefresh();
        removeResizeCursorDispatcher();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    @Override public void dispose() {
        stopQuoteRefresh();
        removeResizeCursorDispatcher();
        super.dispose();
    }

    /**
     * 停掉行情轮询。
     *
     * <p>不这么做的话，窗口关掉之后 {@code Timer} 仍会按周期触发，
     * 在后台持续发请求——用户以为已经退出了，网络活动却还在。
     */
    private void stopQuoteRefresh() {
        if (quoteTimer != null) {
            quoteTimer.stop();
            quoteTimer = null;
        }
    }

    private void exitApplication() {
        saveWindowState();
        shutdown();
        AppPaths.releaseSingleInstanceLock();
        dispose();
        System.exit(0);
    }

    /**
     * 同步托盘里透明度子菜单的勾选状态。
     *
     * <p>AWT 的 {@link CheckboxMenuItem} 没有单选组，只能手工互斥；而 {@code setState}
     * 会反过来触发 ItemEvent，所以必须用标志位挡住，否则会递归。
     */
    private void syncTrayOpacityItems() {
        syncingTrayOpacity = true;
        try {
            trayOpacityItems.forEach((step, item) -> item.setState(step == opacityPercent));
        } finally {
            syncingTrayOpacity = false;
        }
    }

    // ---------------------------------------------------------------- 位置与尺寸

    private void saveWindowState() {
        if (!isShowing()) return;
        preferences.putInt(KEY_WINDOW_X, getX());
        preferences.putInt(KEY_WINDOW_Y, getY());
        preferences.putInt(KEY_WINDOW_WIDTH, getWidth());
        preferences.putInt(KEY_WINDOW_HEIGHT, getHeight());
    }

    /**
     * 恢复上次的窗口尺寸与位置。
     *
     * <p>两步都要用当前屏幕的可用区域收一遍：换显示器、改分辨率、改缩放之后，
     * 上次存下的尺寸可能比屏幕还大，位置也可能整块落在屏幕外再也抓不回来。
     */
    private void restoreWindowGeometry() {
        Rectangle area = usableScreenBounds();
        Dimension size = WindowGeometry.clampSize(
                new Dimension(preferences.getInt(KEY_WINDOW_WIDTH, Theme.DEFAULT_WINDOW_WIDTH),
                        preferences.getInt(KEY_WINDOW_HEIGHT, Theme.DEFAULT_WINDOW_HEIGHT)),
                minimumWindowSize(), area);
        windowWidth = size.width;
        windowHeight = size.height;
        setSize(windowWidth, windowHeight);

        int x = preferences.getInt(KEY_WINDOW_X, Integer.MIN_VALUE);
        int y = preferences.getInt(KEY_WINDOW_Y, Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
            setLocationByPlatform(true);
            return;
        }
        // 留 40px 可见即可，不必要求窗口完整落在屏幕内——用户可能就是故意让它探出一点。
        if (x + windowWidth < area.x + 40 || x > area.x + area.width - 40
                || y + 40 < area.y || y > area.y + area.height - 40) {
            setLocationByPlatform(true);
            return;
        }
        setLocation(x, y);
    }

    // ---------------------------------------------------------------- 调整大小

    /**
     * 安装边缘/角落的调整大小能力。
     *
     * <p>无边框窗口没有原生边框，热区判定与拖拽都要自己实现。拖拽部分挂在卡片上
     * （与移动共用同一套按下/拖动/松手事件），光标部分走全局监听——原因见
     * {@link #installResizeCursorDispatcher()}。
     */
    private void installResizeHandle(Component target) {
        MouseAdapter handle = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                beginResize(event);
            }

            @Override public void mouseDragged(MouseEvent event) {
                continueResize(event);
            }

            @Override public void mouseReleased(MouseEvent event) {
                endResize();
            }
        };
        target.addMouseListener(handle);
        target.addMouseMotionListener(handle);
    }

    /**
     * 全局监听鼠标移动，按热区切换边缘光标。
     *
     * <p>为什么不用普通 {@code MouseListener}：日历格子与日程条目是每次刷新重建的，
     * 挂上去的监听器会随 {@code removeAll()} 一起消失；而且鼠标一旦移进子组件，
     * 父容器的 {@code mouseMoved} 就不再触发，光标会永远卡在边缘箭头上回不来。
     * 全局监听两个问题一起解决，代价只是要记得在 {@code dispose} 时摘掉。
     */
    private void installResizeCursorDispatcher() {
        resizeCursorDispatcher = event -> {
            if (!(event instanceof MouseEvent mouse) || mouse.getID() != MouseEvent.MOUSE_MOVED) return;
            if (SwingUtilities.getWindowAncestor(mouse.getComponent()) != this) return;
            updateResizeCursor(mouse);
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(resizeCursorDispatcher,
                AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    private void removeResizeCursorDispatcher() {
        if (resizeCursorDispatcher == null) return;
        Toolkit.getDefaultToolkit().removeAWTEventListener(resizeCursorDispatcher);
        resizeCursorDispatcher = null;
    }

    private void updateResizeCursor(MouseEvent event) {
        // 拖拽过程中光标由 beginResize 定下，别被移动事件覆盖。
        if (resizeZone != WindowGeometry.ZONE_NONE) return;
        int cursor = WindowGeometry.resizeCursor(
                WindowGeometry.resizeZoneAt(toCardPoint(event), getSize(), Theme.RESIZE_MARGIN));
        if (cursor == appliedResizeCursor) return;
        appliedResizeCursor = cursor;
        getContentPane().setCursor(Cursor.getPredefinedCursor(cursor));
    }

    /** 左键按在边缘热区上才开始调整大小；按在别处交回给移动逻辑。 */
    private void beginResize(MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) return;
        int zone = WindowGeometry.resizeZoneAt(toCardPoint(event), getSize(), Theme.RESIZE_MARGIN);
        if (zone == WindowGeometry.ZONE_NONE) return;
        resizeZone = zone;
        resizeStartBounds = getBounds();
        resizeStartOnScreen = event.getLocationOnScreen();
        getContentPane().setCursor(Cursor.getPredefinedCursor(WindowGeometry.resizeCursor(zone)));
    }

    /**
     * 拖拽中按位移重算窗口矩形。
     *
     * <p>过程里不吸附：与移动一致，"跟手"优先，落位那一刻才吸过去，
     * 否则边拖边被粘住，想停在离边缘 10px 的地方都做不到。
     */
    private void continueResize(MouseEvent event) {
        if (resizeZone == WindowGeometry.ZONE_NONE || resizeStartBounds == null) return;
        Point current = event.getLocationOnScreen();
        setBounds(WindowGeometry.resizedBounds(resizeStartBounds, resizeZone,
                current.x - resizeStartOnScreen.x, current.y - resizeStartOnScreen.y,
                minimumWindowSize(), maximumWindowSize()));
    }

    private void endResize() {
        if (resizeZone == WindowGeometry.ZONE_NONE) return;
        int zone = resizeZone;
        resizeZone = WindowGeometry.ZONE_NONE;
        resizeStartBounds = null;
        resizeStartOnScreen = null;
        appliedResizeCursor = -1;

        Rectangle area = usableScreenBounds();
        Rectangle bounds = snapEnabled
                ? WindowGeometry.snappedResize(getBounds(), zone, area) : getBounds();
        // 吸附可能把边推到比最大尺寸还远，这里再收一次。
        bounds.setSize(WindowGeometry.clampSize(bounds.getSize(), minimumWindowSize(), area));
        setBounds(bounds);
        saveWindowState();
    }

    /** 恢复到默认尺寸。用户把窗口拉变形之后需要一条明确的退路。 */
    public void resetWindowSize() {
        setSize(WindowGeometry.clampSize(Theme.defaultWindowSize(),
                minimumWindowSize(), usableScreenBounds()));
        saveWindowState();
        // 尺寸变了，原来的贴边位置可能已经不合身，重新贴一次。
        snapToScreenEdges();
    }

    /** 窗口尺寸上限：不超过当前屏幕的可用区域，免得拉出屏幕再也抓不回来。 */
    private Dimension maximumWindowSize() {
        Rectangle area = usableScreenBounds();
        Dimension minimum = minimumWindowSize();
        return new Dimension(Math.max(minimum.width, area.width),
                Math.max(minimum.height, area.height));
    }

    private Point toCardPoint(MouseEvent event) {
        return SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), getContentPane());
    }

    /** 无边框窗口的拖拽移动。 */
    private void installDrag(Component target) {
        MouseAdapter dragListener = new MouseAdapter() {
            private Point pressOnScreen;
            private Point windowAtPress;

            @Override public void mousePressed(MouseEvent event) {
                // 只认左键：右键要留给上下文菜单，否则菜单弹出时会连带把窗口拽走。
                if (event.getButton() != MouseEvent.BUTTON1) return;
                // 边缘热区归"调整大小"。这里重新算一次而不是读 resizeZone，
                // 是为了不依赖同一组件上两个监听器的触发顺序。
                if (WindowGeometry.resizeZoneAt(toCardPoint(event), getSize(), Theme.RESIZE_MARGIN)
                        != WindowGeometry.ZONE_NONE) {
                    return;
                }
                pressOnScreen = event.getLocationOnScreen();
                windowAtPress = getLocation();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (resizeZone != WindowGeometry.ZONE_NONE) return;
                if (pressOnScreen == null || windowAtPress == null) return;
                Point current = event.getLocationOnScreen();
                setLocation(windowAtPress.x + (current.x - pressOnScreen.x),
                        windowAtPress.y + (current.y - pressOnScreen.y));
            }

            @Override public void mouseReleased(MouseEvent event) {
                boolean wasDragging = pressOnScreen != null;
                pressOnScreen = null;
                windowAtPress = null;
                // 松手才吸附：拖动过程跟手，落位时才"吸"过去，不会中途被粘住。
                if (wasDragging) snapToScreenEdges();
            }
        };
        target.addMouseListener(dragListener);
        target.addMouseMotionListener(dragListener);
    }

    /** 卡片上的右键菜单。每次弹出时重建，勾选状态自然与当前配置同步。 */
    private void installContextMenu(Component target) {
        MouseAdapter popupListener = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { showIfPopup(event); }

            @Override public void mouseReleased(MouseEvent event) { showIfPopup(event); }

            private void showIfPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                createContextMenu().show(event.getComponent(), event.getX(), event.getY());
            }
        };
        target.addMouseListener(popupListener);
    }

    /** 卡片上的右键菜单。每次弹出时重建，勾选状态自然与当前配置同步。 */
    public JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setFont(Theme.FONT);

        JMenu opacityMenu = new JMenu("透明度");
        opacityMenu.setFont(Theme.FONT);
        if (opacitySupported) {
            ButtonGroup group = new ButtonGroup();
            for (int step : Theme.OPACITY_STEPS) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(step + "%");
                item.setFont(Theme.FONT);
                item.setSelected(step == opacityPercent);
                item.addActionListener(e -> setOpacityPercent(step));
                group.add(item);
                opacityMenu.add(item);
            }
        } else {
            JMenuItem unsupported = new JMenuItem("当前平台不支持");
            unsupported.setFont(Theme.FONT);
            unsupported.setEnabled(false);
            opacityMenu.add(unsupported);
        }
        menu.add(opacityMenu);

        JCheckBoxMenuItem snapItem = new JCheckBoxMenuItem("贴边吸附", snapEnabled);
        snapItem.setFont(Theme.FONT);
        snapItem.setToolTipText("拖动松手时自动贴合屏幕边缘");
        snapItem.addActionListener(e -> setSnapEnabled(snapItem.isSelected()));
        menu.add(snapItem);

        menu.addSeparator();
        // 行情与历史都是可选内容，默认开着，但不关心股市的用户应该能一键收掉，
        // 把高度还给日程列表。
        JCheckBoxMenuItem quotesItem = new JCheckBoxMenuItem("显示行情条", showQuotes);
        quotesItem.setFont(Theme.FONT);
        quotesItem.setToolTipText("在今日卡片下方显示指数与自选股的涨跌");
        quotesItem.addActionListener(e -> setShowQuotes(quotesItem.isSelected()));
        menu.add(quotesItem);

        JCheckBoxMenuItem historyItem = new JCheckBoxMenuItem("显示历史上的今天", showHistory);
        historyItem.setFont(Theme.FONT);
        historyItem.setToolTipText("在日程列表下方显示当天的历史事件入口");
        historyItem.addActionListener(e -> setShowHistory(historyItem.isSelected()));
        menu.add(historyItem);

        JMenuItem watchlistItem = new JMenuItem("管理自选股…");
        watchlistItem.setFont(Theme.FONT);
        watchlistItem.setToolTipText("添加或移除行情条上关注的股票");
        watchlistItem.addActionListener(e -> openWatchlistDialog());
        menu.add(watchlistItem);

        menu.addSeparator();
        JMenuItem todayItem = new JMenuItem("回到今天");
        todayItem.setFont(Theme.FONT);
        todayItem.addActionListener(e -> goToday());
        menu.add(todayItem);

        JMenuItem modeItem = new JMenuItem(monthMode ? "切换到今日视图" : "切换到月历视图");
        modeItem.setFont(Theme.FONT);
        modeItem.addActionListener(e -> toggleMode());
        menu.add(modeItem);

        JMenuItem resetSizeItem = new JMenuItem("重置窗口大小");
        resetSizeItem.setFont(Theme.FONT);
        resetSizeItem.setToolTipText("恢复到默认的 " + Theme.DEFAULT_WINDOW_WIDTH + "×"
                + Theme.DEFAULT_WINDOW_HEIGHT + "（拖动窗口边缘或右下角可自由调整）");
        resetSizeItem.addActionListener(e -> resetWindowSize());
        menu.add(resetSizeItem);

        menu.addSeparator();
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.setFont(Theme.FONT);
        exitItem.addActionListener(e -> exitApplication());
        menu.add(exitItem);
        return menu;
    }

    // ---------------------------------------------------------------- 透明度

    /** 设置窗口不透明度（百分比）并持久化。 */
    public void setOpacityPercent(int percent) {
        opacityPercent = percent;
        preferences.putInt(KEY_OPACITY_PERCENT, percent);
        applyOpacity();
        syncTrayOpacityItems();
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    private void applyOpacity() {
        if (!opacitySupported) return;
        try {
            setOpacity(Math.max(0.2f, Math.min(1f, opacityPercent / 100f)));
        } catch (RuntimeException exception) {
            // 探测通过但实际设置失败（远程桌面、部分显卡驱动），静默降级为不透明。
        }
    }

    // ---------------------------------------------------------------- 贴边吸附

    public void setSnapEnabled(boolean enabled) {
        snapEnabled = enabled;
        preferences.putBoolean(KEY_SNAP_ENABLED, enabled);
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    /** 拖动结束后把窗口吸附到屏幕可用区域的边缘。 */
    private void snapToScreenEdges() {
        if (!snapEnabled) return;
        Point snapped = WindowGeometry.snappedLocation(getLocation(), getSize(), usableScreenBounds());
        if (!snapped.equals(getLocation())) {
            setLocation(snapped);
            saveWindowState();
        }
    }

    /**
     * 当前窗口所在显示器的可用区域。
     *
     * <p>多显示器下取窗口当前所在的那块屏，而不是主屏。
     */
    private Rectangle usableScreenBounds() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        }
        return WindowGeometry.usableArea(configuration.getBounds(),
                Toolkit.getDefaultToolkit().getScreenInsets(configuration));
    }

    private static MenuItem trayMenuItem(String label) {
        MenuItem item = new MenuItem(label);
        item.setFont(Theme.NATIVE_MENU_FONT);
        return item;
    }
}
