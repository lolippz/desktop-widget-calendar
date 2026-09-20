package com.calendar.service;

import com.calendar.model.NewsItem;
import com.calendar.util.Json;
import com.calendar.util.Net;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 财经要闻获取。
 *
 * <p>主源是东方财富的快讯列表。这个接口有一个必须记住的坑：
 * <b>带上 {@code sortEnd} 参数会稳定返回空列表</b>——响应是 200、{@code code} 也是
 * "1"、{@code message} 是 "success"，只有 {@code list} 是空的，排查起来极具误导性。
 * 不传该参数时同一接口正常返回数据，所以这里刻意不传。
 *
 * <p>备源是新浪财经滚动。两个源的字段名完全不同，各自解析后统一成 {@link NewsItem}。
 *
 * <p>要闻属于"有就看一眼、没有也不影响用"的内容，所以任何一个源返回空都直接
 * 视为失败，不重试、不提示，交由界面隐藏该区块。
 */
public final class NewsService {

    private static final String EASTMONEY_URL =
            "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns"
                    + "?client=web&biz=web_news_col&column=350&order=1&needInteractData=0"
                    + "&page_index=1&page_size=%d&req_trace=%d"
                    + "&fields=code,showTime,title,mediaName&types=1,20";
    private static final String EASTMONEY_REFERER = "https://kuaixun.eastmoney.com/";

    private static final String SINA_URL =
            "https://feed.mix.sina.com.cn/api/roll/get?pageid=155&lid=1686&num=%d&page=1";
    private static final String SINA_REFERER = "https://finance.sina.com.cn/";

    /** 只取时分。挂件上不需要知道是哪天，列表本身按时间倒序，最新的一定在最上面。 */
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm");

    private NewsService() { }

    /**
     * 获取最新的若干条要闻。
     *
     * @param limit 期望条数
     * @return 按时间倒序排列的要闻；取不到时返回空列表而非抛异常
     */
    public static List<NewsItem> fetch(int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        try {
            List<NewsItem> items = fetchFromEastMoney(size);
            if (!items.isEmpty()) return items;
        } catch (IOException | RuntimeException ignored) {
            // 主源失败，继续试备源。
        }
        try {
            return fetchFromSina(size);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<NewsItem> fetchFromEastMoney(int limit) throws IOException {
        String url = String.format(EASTMONEY_URL, limit, System.currentTimeMillis());
        String body = Net.get(url, EASTMONEY_REFERER);
        Object root = Json.parse(body);
        List<Object> list = Json.array(Json.field(root, "data"), "list");

        List<NewsItem> items = new ArrayList<>();
        for (Object node : list) {
            String title = Json.string(node, "title").trim();
            if (title.isEmpty()) continue;
            items.add(new NewsItem(shortTime(Json.string(node, "showTime")), title,
                    Json.string(node, "mediaName"), ""));
        }
        return items;
    }

    private static List<NewsItem> fetchFromSina(int limit) throws IOException {
        String url = String.format(SINA_URL, limit);
        String body = Net.get(url, SINA_REFERER);
        Object root = Json.parse(body);
        List<Object> list = Json.array(Json.field(root, "result"), "data");

        List<NewsItem> items = new ArrayList<>();
        for (Object node : list) {
            String title = Json.string(node, "title").trim();
            if (title.isEmpty()) continue;
            // 新浪的媒体名字段在不同栏目里叫法不一致，逐个尝试。
            String source = firstNonBlank(
                    Json.string(node, "media_name"),
                    Json.string(node, "mediaName"),
                    Json.string(node, "author"));
            items.add(new NewsItem(epochToTime(Json.string(node, "ctime")), title,
                    source, Json.string(node, "url")));
        }
        return items;
    }

    /** {@code "2026-09-20 06:10:00"} → {@code "06:10"}。格式不符时原样返回。 */
    private static String shortTime(String raw) {
        String value = raw == null ? "" : raw.trim();
        int space = value.indexOf(' ');
        if (space < 0 || value.length() < space + 6) return value;
        return value.substring(space + 1, space + 6);
    }

    /** 新浪给的是 Unix 秒，转成本地时区的时分。 */
    private static String epochToTime(String raw) {
        try {
            long seconds = Long.parseLong(raw.trim());
            return TIME_ONLY.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException ignored) {
            // 覆盖 NumberFormatException 与超范围时间戳两类情况。
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
