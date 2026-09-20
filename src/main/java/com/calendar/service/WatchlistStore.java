package com.calendar.service;

import com.calendar.data.AppPaths;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * 自选股列表的持久化。
 *
 * <p>存在首选项里而不是 SQLite 里，是因为它和窗口位置、透明度属于同一类数据：
 * 条目少（上限 20）、整体读写（从来不会只更新其中一条）、丢了也只是重新加一遍。
 * 为它建一张表反而要处理建表、迁移、事务这些与收益不相称的复杂度。
 *
 * <p>只存代码，不存名称。名称由行情接口实时返回，存下来反而会变成一份
 * 需要同步的冗余数据——股票改名（ST 摘帽之类）之后界面就会显示旧名字。
 */
public final class WatchlistStore {

    private static final String KEY_WATCHLIST = "watchlist";

    /** 上限。挂件里超过 20 个标的，行情条会先撑不住，不如一开始就设个边界。 */
    public static final int MAX_ITEMS = 20;

    private final Preferences preferences;

    public WatchlistStore() {
        this(Preferences.userRoot().node(AppPaths.PREFERENCES_PATH));
    }

    /** 允许注入首选项，便于测试隔离。 */
    public WatchlistStore(Preferences preferences) {
        this.preferences = preferences;
    }

    /** 读取自选股代码，已归一化。 */
    public List<String> load() {
        String raw = preferences.get(KEY_WATCHLIST, "");
        if (raw.isBlank()) return new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (String part : raw.split(",")) {
            String code = QuoteService.normalize(part);
            if (!code.isEmpty() && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 覆盖保存。超出上限的尾部会被丢弃。 */
    public void save(List<String> codes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(QuoteService.normalizeAll(codes));
        List<String> limited = new ArrayList<>(unique);
        if (limited.size() > MAX_ITEMS) {
            limited = limited.subList(0, MAX_ITEMS);
        }
        preferences.put(KEY_WATCHLIST, String.join(",", limited));
        flush();
    }

    /**
     * 添加一只。
     *
     * @return 实际新增返回 true；已存在或已达上限返回 false
     */
    public boolean add(String rawCode) {
        String code = QuoteService.normalize(rawCode);
        if (code.isEmpty()) return false;
        List<String> codes = load();
        if (codes.contains(code) || codes.size() >= MAX_ITEMS) return false;
        codes.add(code);
        save(codes);
        return true;
    }

    /** @return 确实删掉了返回 true */
    public boolean remove(String code) {
        List<String> codes = load();
        if (!codes.remove(QuoteService.normalize(code))) return false;
        save(codes);
        return true;
    }

    public boolean contains(String rawCode) {
        return load().contains(QuoteService.normalize(rawCode));
    }

    public int size() {
        return load().size();
    }

    public boolean isFull() {
        return load().size() >= MAX_ITEMS;
    }

    private void flush() {
        try {
            preferences.flush();
        } catch (Exception ignored) {
            // 首选项落盘失败不影响本次会话，下次启动会退回上一次的值。
        }
    }
}
