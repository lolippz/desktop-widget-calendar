package com.calendar.service;

import com.calendar.data.AppPaths;
import com.calendar.model.HistoryEvent;
import com.calendar.util.Json;
import com.calendar.util.Net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * "历史上的今天"获取。
 *
 * <p>数据按<b>自然日</b>缓存到数据目录。这么做有两个理由：
 * <ul>
 *   <li>历史事件是按日期固定的，同一天内反复请求只会得到完全相同的结果；</li>
 *   <li>数据源是个人站点，可用性不如商业接口，缓存让它在偶发故障时仍然有内容可显示。</li>
 * </ul>
 *
 * <p>缓存文件用原始 JSON 存，不重新序列化——省掉一个序列化器，也让缓存文件
 * 出问题时可以直接打开看内容。
 */
public final class HistoryService {

    private static final String SOURCE_URL = "https://tmini.net/api/today?type=json";
    private static final String REFERER = "https://tmini.net/";

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 缓存保留天数。挂件只会用到当天的，留几天是为了避免跨天后立刻删掉"昨天"的文件。 */
    private static final int KEEP_DAYS = 7;

    /** 超过这个条数就只留前面这些。列表太长在挂件里没有意义，反而拖慢渲染。 */
    private static final int MAX_EVENTS = 30;

    private HistoryService() { }

    /**
     * 取某个日期的历史事件。
     *
     * <p>只支持<b>当天</b>：上游接口没有日期参数，给不了任意日期的数据。
     * 传入其他日期直接返回空列表，由界面隐藏该区块——而不是拿今天的事件
     * 去冒充那一天，那样只会误导用户。
     */
    public static List<HistoryEvent> forDate(LocalDate date) {
        if (date == null || !date.equals(LocalDate.now())) return List.of();

        String cached = readCache(date);
        if (cached != null) {
            List<HistoryEvent> events = parse(cached);
            if (!events.isEmpty()) return events;
        }
        try {
            String body = Net.get(SOURCE_URL, REFERER);
            List<HistoryEvent> events = parse(body);
            if (!events.isEmpty()) {
                writeCache(date, body);
                pruneCache();
            }
            return events;
        } catch (IOException | RuntimeException ignored) {
            // 缓存没有、网络也失败，返回空——界面会把这个区块整块隐藏，不显示报错。
            return List.of();
        }
    }

    private static List<HistoryEvent> parse(String body) {
        try {
            Object root = Json.parse(body);
            List<Object> list = Json.array(root, "events");
            List<HistoryEvent> events = new ArrayList<>();
            for (Object node : list) {
                String title = Json.string(node, "title").trim();
                if (title.isEmpty()) continue;
                events.add(new HistoryEvent(
                        Json.string(node, "year").trim(),
                        title,
                        Json.string(node, "desc").trim(),
                        Json.string(node, "link").trim()));
            }
            // 年份是字符串，直接比较会出现"199"排在"1990"之后的问题，所以按数值排。
            events.sort(Comparator.comparingInt(HistoryService::yearOf).reversed());
            return events.size() > MAX_EVENTS ? events.subList(0, MAX_EVENTS) : events;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static int yearOf(HistoryEvent event) {
        try {
            return Integer.parseInt(event.year().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 缓存

    private static Path cacheFile(LocalDate date) {
        return AppPaths.DATA_DIR.resolve("history-" + FILE_DATE.format(date) + ".json");
    }

    private static String readCache(LocalDate date) {
        Path file = cacheFile(date);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writeCache(LocalDate date, String body) {
        try {
            Files.writeString(cacheFile(date), body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 缓存写不进去不影响本次显示，下次重新请求即可。
        }
    }

    /** 删除过期的缓存文件，避免数据目录里越积越多。 */
    private static void pruneCache() {
        LocalDate cutoff = LocalDate.now().minusDays(KEEP_DAYS);
        try (Stream<Path> files = Files.list(AppPaths.DATA_DIR)) {
            files.filter(path -> path.getFileName().toString().startsWith("history-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        LocalDate date = parseFileName(path);
                        if (date != null && date.isBefore(cutoff)) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // 删不掉就留着，不值得为此打扰用户。
                            }
                        }
                    });
        } catch (IOException ignored) {
            // 目录读取失败不影响主流程。
        }
    }

    private static LocalDate parseFileName(Path path) {
        String name = path.getFileName().toString();
        try {
            return LocalDate.parse(name.substring("history-".length(), name.length() - ".json".length()),
                    FILE_DATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
