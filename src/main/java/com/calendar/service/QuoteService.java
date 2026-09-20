package com.calendar.service;

import com.calendar.model.Quote;
import com.calendar.util.Json;
import com.calendar.util.Net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行情获取。
 *
 * <p>主源是新浪 {@code hq.sinajs.cn}。选它的理由不是偏好，是实测结果：
 * <ul>
 *   <li>一次请求能带十几个标的（实测 18 个代码 URL 仅 182 字符，响应 4.8KB），
 *       指数与自选股可以合并成一次网络往返；</li>
 *   <li>同时覆盖 A 股、美股、港股，不必为不同市场写不同的取数逻辑；</li>
 *   <li>连打 5 次全部 200、耗时 180–240ms，稳定性足够支撑挂件的轮询。</li>
 * </ul>
 *
 * <p>东方财富 {@code push2} 已被排除：同一个主机下 {@code clist/get} 通、
 * {@code stock/get} 报 "HTTP/1.1 header parser received no bytes"，且两次运行结果
 * 不一致——这种"时通时不通"的源做不了挂件的骨架。
 *
 * <p>备源是腾讯 {@code proxy.finance.qq.com}。它的 K 线接口响应里顺带带了
 * {@code qt} 实时快照字段，可以独立解析出行情，因此不需要额外找一个快照接口。
 */
public final class QuoteService {

    /** 挂件默认展示的三个指数：纳指100 + 沪深两市大盘。 */
    public static final List<String> DEFAULT_INDEX_CODES =
            List.of("gb_ndx", "sh000001", "sz399001");

    /** 指数显示名。接口返回的名称偶有差异（"纳斯达克100" / "NASDAQ 100"），统一成固定文案。 */
    private static final Map<String, String> INDEX_LABELS = Map.of(
            "gb_ndx", "纳指100",
            "gb_dji", "道琼斯",
            "gb_ixic", "纳斯达克",
            "gb_spx", "标普500",
            "sh000001", "上证指数",
            "sz399001", "深证成指",
            "sz399006", "创业板指",
            "sh000300", "沪深300",
            "rt_hkhsi", "恒生指数");

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    private static final String SINA_REFERER = "https://finance.sina.com.cn/";
    private static final String TENCENT_URL =
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=";
    private static final String TENCENT_REFERER = "https://gu.qq.com/";

    /**
     * 单次请求的代码数上限。
     *
     * <p>实测 18 个代码的 URL 只有 182 字符，离常见网关的 2KB 上限很远，
     * 所以这里限制的不是 URL 长度，而是防止用户把自选股加到几十个之后
     * 单次响应过大——真到那个规模，分批取数比一次取完更稳。
     */
    private static final int BATCH_SIZE = 40;

    private static final Pattern SINA_ENTRY =
            Pattern.compile("hq_str_([A-Za-z0-9_]+)=\"([^\"]*)\"");

    /**
     * 逐条重试的上限。
     *
     * <p>被拒是异常路径，正常情况下不会走到；但真走到时也不能让一次刷新
     * 被拖成几十秒，所以超出的部分这一轮直接放弃。
     */
    private static final int ISOLATION_LIMIT = 20;

    /**
     * 数据源能接受的代码形态，四类：沪/深/北 6 位数字、港股 {@code rt_hk00700}
     * （指数形如 {@code rt_hkhsi}）、港股简写 {@code hk00700}、美股 {@code gb_aapl}。
     *
     * <p>这个正则不只是"好看"。实测新浪对不符合它的 token 会返回
     * {@code var hq_str_sys_auth="FAILED";}，并且<b>把同批请求一起作废</b>——
     * 一个 {@code 61666} 就能让整批指数和自选股全部拿不到。所以它同时是
     * 发请求前的准入门槛，而不是事后的校验。
     */
    private static final Pattern VALID_CODE = Pattern.compile(
            "(sh|sz|bj)\\d{6}|rt_hk[a-z0-9]+|hk\\d{5}|gb_[a-z][a-z.]*");

    /** 纯 6 位数字，按首位补市场前缀。 */
    private static final Pattern BARE_CHINA = Pattern.compile("\\d{6}");

    /** 纯字母（可含点），如 {@code AAPL}、{@code BRK.B}。 */
    private static final Pattern BARE_OVERSEAS = Pattern.compile("[a-z][a-z.]*");

    /** 只写了市场前缀没跟代码。这类输入要报格式错，不能当成美股去查。 */
    private static final List<String> LONE_PREFIXES = List.of("sh", "sz", "bj", "hk", "gb");

    /** 新浪对非法 token 的拒绝标记。 */
    private static final String REJECT_MARKER = "sys_auth";

    private QuoteService() { }

    /**
     * 数据源拒绝了本次请求。
     *
     * <p>与"网络不通"是两件事：前者说明请求被对方挡下来了（请求本身送达了），
     * 后者说明请求没送到。区分开才能给出不说谎的提示——把格式错误或代码
     * 不存在说成"网络不可用"，用户只会去反复检查自己的网络。
     */
    public static class RejectedException extends IOException {
        public RejectedException(String message) {
            super(message);
        }
    }

    /**
     * 数据源应答正常，但没有这些代码的行情。
     *
     * <p>与"网络不通"必须分开。新浪对一个不存在的代码（{@code sh999999}）返回
     * {@code var hq_str_sh999999="";} ——HTTP 200、响应完整、语义明确地表示
     * "查无此码"。把这种情况说成"网络不可用"，用户会去查网络，而真正要改的是代码。
     */
    public static class NotFoundException extends IOException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 批量获取行情。
     *
     * <p>先走新浪，整批失败才降级到腾讯。不做"部分降级"——混用两个源的字段口径
     * 容易在界面上出现前后不一致的数字，宁可这一轮全部不显示。
     *
     * @param codes 数据源代码（{@code sh000001} / {@code gb_ndx}），会先做归一化
     * @return 按传入顺序排列的行情，取不到的代码会被跳过
     * @throws IOException 两个源都不可用时抛出，由调用方决定隐藏还是提示
     */
    public static List<Quote> fetch(List<String> codes) throws IOException {
        List<String> normalized = normalizeAll(codes);
        if (normalized.isEmpty()) return List.of();

        try {
            return fetchFromSina(normalized);
        } catch (IOException sinaFailure) {
            try {
                return fetchFromTencent(normalized);
            } catch (IOException tencentFailure) {
                // 主源的诊断更有代表性——绝大多数代码由它承载，它说"查无此码"
                // 比备源说"网络不通"更接近事实。把备源的失败挂在它下面即可。
                sinaFailure.addSuppressed(tencentFailure);
                throw sinaFailure;
            }
        }
    }

    // ------------------------------------------------------------ 新浪主源

    private static List<Quote> fetchFromSina(List<String> codes) throws IOException {
        List<Quote> quotes = new ArrayList<>();
        boolean rejected = false;
        for (int start = 0; start < codes.size(); start += BATCH_SIZE) {
            List<String> batch = codes.subList(start, Math.min(start + BATCH_SIZE, codes.size()));
            // 发请求前再过一道门槛。上游 normalize 已经过滤过，这里是第二道——
            // 代价是零，而漏一个非法 token 的代价是整批行情消失。
            List<String> allowed = new ArrayList<>();
            for (String code : batch) {
                if (isValid(code)) allowed.add(code);
            }
            if (allowed.isEmpty()) continue;

            String body = Net.get(SINA_URL + String.join(",", allowed), SINA_REFERER, Net.GBK);
            if (isRejected(body)) {
                rejected = true;
                quotes.addAll(fetchSinaIndividually(allowed));
                continue;
            }
            Map<String, Quote> parsed = parseSina(body);
            // 按请求顺序回填：接口的返回顺序不保证与请求一致。
            for (String code : allowed) {
                Quote quote = parsed.get(code);
                if (quote != null) quotes.add(quote);
            }
        }
        if (quotes.isEmpty()) {
            // 三种结果要分开，否则界面只能给一句笼统的"网络不可用"：
            // 被拒绝（请求被挡下）、查无此码（应答正常但没数据）、其余按失败处理。
            if (rejected) throw new RejectedException("行情源拒绝了本次请求");
            throw new NotFoundException("数据源未返回这些代码的行情");
        }
        return quotes;
    }

    /**
     * 整批被拒时的补救：拆成单条重试。
     *
     * <p>新浪拒绝的是"请求里带了非法 token"，所以逐条发时合法的那几条能活下来，
     * 只有真正非法的会被拒。代价是 N 次请求，但这是异常路径，且能避免
     * "一个坏代码让整个行情条消失"这种最糟的结果。
     */
    private static List<Quote> fetchSinaIndividually(List<String> batch) {
        List<Quote> quotes = new ArrayList<>();
        int limit = Math.min(batch.size(), ISOLATION_LIMIT);
        for (int index = 0; index < limit; index++) {
            String code = batch.get(index);
            try {
                String body = Net.get(SINA_URL + code, SINA_REFERER, Net.GBK);
                if (isRejected(body)) continue;
                Quote quote = parseSina(body).get(code);
                if (quote != null) quotes.add(quote);
            } catch (IOException ignored) {
                // 单条失败不影响其余；全部失败时上层统一处理。
            }
        }
        return quotes;
    }

    /** 新浪对非法 token 的回应：{@code var hq_str_sys_auth="FAILED";}。 */
    private static boolean isRejected(String body) {
        return body != null && body.contains(REJECT_MARKER);
    }

    private static Map<String, Quote> parseSina(String body) {
        Map<String, Quote> result = new LinkedHashMap<>();
        Matcher matcher = SINA_ENTRY.matcher(body);
        while (matcher.find()) {
            String code = matcher.group(1).toLowerCase();
            String payload = matcher.group(2).trim();
            // 停牌或代码错误时新浪返回空串，不是错误，跳过即可。
            if (payload.isEmpty()) continue;
            // sys_auth 是拒绝标记而不是行情条目，显式跳过，别让它进解析分支。
            if (code.equals(REJECT_MARKER)) continue;
            String[] fields = payload.split(",");
            Quote quote = switch (marketOf(code)) {
                case UNITED_STATES -> parseSinaUs(code, fields);
                case HONG_KONG -> parseSinaHongKong(code, fields);
                default -> parseSinaChina(code, fields);
            };
            if (quote != null) result.put(code, quote);
        }
        return result;
    }

    /**
     * A 股（含沪深指数）字段顺序：
     * {@code 名称,今开,昨收,最新价,最高,最低,买一,卖一,成交量,成交额,…}
     *
     * <p>注意这里<b>没有</b>涨跌额和涨跌幅字段，必须自己用最新价和昨收算。
     */
    private static Quote parseSinaChina(String code, String[] fields) {
        if (fields.length < 4) return null;
        double price = number(fields[3]);
        double previousClose = number(fields[2]);
        if (price <= 0) return null;
        double change = price - previousClose;
        double percent = previousClose > 0 ? change / previousClose * 100 : 0;
        return new Quote(code, labelFor(code, fields[0]), price, change, percent, Quote.Market.CHINA);
    }

    /**
     * 美股字段顺序：
     * {@code 名称,最新价,涨跌幅,时间,涨跌额,开盘,最高,最低,52周高,52周低,…}
     *
     * <p>与 A 股完全不同：涨跌幅在索引 2、涨跌额在索引 4，都是现成的，不要自己算——
     * 美股昨收不在响应里，硬算只能得到错误结果。
     */
    private static Quote parseSinaUs(String code, String[] fields) {
        if (fields.length < 5) return null;
        double price = number(fields[1]);
        double percent = number(fields[2]);
        double change = number(fields[4]);
        if (price <= 0) return null;
        return new Quote(code, labelFor(code, fields[0]), price, change, percent, Quote.Market.UNITED_STATES);
    }

    /**
     * 港股字段顺序：
     * {@code 代码,名称,今开,昨收,最高,最低,最新价,涨跌额,涨跌幅,…}
     *
     * <p>名称在索引 1（不是 0），最新价在索引 6，与 A 股、美股又都不同。
     */
    private static Quote parseSinaHongKong(String code, String[] fields) {
        if (fields.length < 9) return null;
        double price = number(fields[6]);
        if (price <= 0) return null;
        return new Quote(code, labelFor(code, fields[1]), price,
                number(fields[7]), number(fields[8]), Quote.Market.HONG_KONG);
    }

    // ------------------------------------------------------------ 腾讯备源

    /**
     * 腾讯备源：逐个标的请求。
     *
     * <p>它的接口不支持一次带多个代码，所以备源在自选股较多时会明显更慢——
     * 这正是它只做备源的原因。上限 12 个，超出的部分宁可缺也不要把降级路径拖成几十秒。
     */
    private static List<Quote> fetchFromTencent(List<String> codes) throws IOException {
        List<Quote> quotes = new ArrayList<>();
        int limit = Math.min(codes.size(), 12);
        for (int index = 0; index < limit; index++) {
            String code = codes.get(index);
            try {
                String body = Net.get(TENCENT_URL + code + ",day,,,1,qfq", TENCENT_REFERER);
                Quote quote = parseTencent(code, body);
                if (quote != null) quotes.add(quote);
            } catch (IOException ignored) {
                // 单个标的失败不影响其余；全部失败时下面统一抛。
            }
        }
        if (quotes.isEmpty()) {
            throw new NotFoundException("腾讯未返回任何有效行情");
        }
        return quotes;
    }

    /**
     * 从腾讯 K 线响应里取出 {@code qt} 快照。
     *
     * <p>{@code qt} 是个数组，索引口径与新浪都不同，实测得出：
     * 3=最新价、4=昨收、5=今开、31=涨跌额、32=涨跌幅。
     */
    private static Quote parseTencent(String code, String body) {
        try {
            Object root = Json.parse(body);
            Object data = Json.field(root, "data");
            Object entry = Json.field(data, code);
            Object qt = Json.field(entry, "qt");
            List<Object> snapshot = Json.array(qt, code);
            if (snapshot.size() < 33) return null;
            double price = number(snapshot.get(3));
            if (price <= 0) return null;
            String name = snapshot.get(1) == null ? "" : snapshot.get(1).toString();
            return new Quote(code, labelFor(code, name), price,
                    number(snapshot.get(31)), number(snapshot.get(32)), marketOf(code));
        } catch (RuntimeException exception) {
            // 备源本身就可能在接口改版后失效，解析异常当作"这条取不到"处理。
            return null;
        }
    }

    // ------------------------------------------------------------ 代码处理

    /**
     * 把用户输入或存储的代码归一化成数据源能识别的形式。
     *
     * <p>用户在自选股里多半只会填 6 位数字，而新浪要求带市场前缀，
     * 所以这里补全：6/9 开头进沪市，0/3 开头进深市，4/8 开头进北交所，纯字母当美股。
     *
     * <p>存在一个无法消解的歧义：{@code 000001} 既是上证指数（{@code sh000001}）
     * 也是平安银行（{@code sz000001}）。这里按<b>个股</b>解释——自选股的语境下
     * 用户想加的是股票，指数应当写全 {@code sh000001}。
     *
     * <p><b>识别不了的一律返回空串。</b>这一点曾经写错过：早先的实现把兜底分支
     * 写成"原样返回"，于是 {@code 61666} 这种 5 位数字会被当成合法代码发出去。
     * 新浪对此返回 {@code sys_auth="FAILED"} 并作废整批请求，界面上却显示成
     * "网络不可用"——用户完全无从判断问题出在自己输入的位数上。
     * 宁可返回空串让调用方报格式错，也不要放一个可能污染整批的 token 出去。
     *
     * @return 归一化后的代码；无法识别时返回空串
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String code = raw.trim().toLowerCase().replace(" ", "");
        if (code.isEmpty()) return "";
        // 已带市场前缀：前缀必须配正确位数的数字，光有前缀不算。
        if (VALID_CODE.matcher(code).matches()) return code;
        // 纯 6 位数字：按首位补前缀。
        if (BARE_CHINA.matcher(code).matches()) {
            char first = code.charAt(0);
            if (first == '6' || first == '9') return "sh" + code;
            if (first == '4' || first == '8') return "bj" + code;
            return "sz" + code;
        }
        // 纯字母：美股。单字母也是合法代码（F / T / V），所以不设长度下限；
        // 但"只写了市场前缀"要排除，否则 sh 会被悄悄当成美股 gb_sh。
        if (BARE_OVERSEAS.matcher(code).matches()) {
            if (LONE_PREFIXES.contains(code)) return "";
            return "gb_" + code.replace(".", "");
        }
        return "";
    }

    /**
     * 判断一个<b>已归一化</b>的代码是否是数据源可接受的形态。
     *
     * <p>供发请求前的准入检查使用。注意它不判断代码是否真实存在——
     * 那只有请求一次才知道。
     */
    public static boolean isValid(String normalizedCode) {
        return normalizedCode != null && VALID_CODE.matcher(normalizedCode).matches();
    }

    /**
     * 给出格式错误的具体原因，用于界面提示。
     *
     * <p>"无法识别的代码格式"这种话用户看了还是不知道该改什么。
     * 能说出"你输入了 5 位"就直说。
     */
    public static String formatHint(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "请输入代码";
        if (text.matches("\\d+")) {
            return "A 股代码是 6 位数字，你输入了 " + text.length() + " 位";
        }
        if (text.matches("(?i)(sh|sz|bj|hk|gb)")) {
            return "只写了市场前缀，" + text + " 后面还要跟代码";
        }
        return "无法识别的代码格式，A 股 6 位数字 / 美股字母代码";
    }

    /** 归一化并去重，保持输入顺序。 */
    public static List<String> normalizeAll(List<String> codes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            String normalized = normalize(code);
            if (!normalized.isEmpty()) unique.add(normalized);
        }
        return new ArrayList<>(unique);
    }

    /** 代码对应的市场。 */
    public static Quote.Market marketOf(String code) {
        String value = code == null ? "" : code.toLowerCase();
        if (value.startsWith("gb_")) return Quote.Market.UNITED_STATES;
        if (value.startsWith("rt_hk") || value.startsWith("hk")) return Quote.Market.HONG_KONG;
        if (value.startsWith("sh") || value.startsWith("sz") || value.startsWith("bj")) {
            return Quote.Market.CHINA;
        }
        return Quote.Market.UNKNOWN;
    }

    /** 指数用固定文案，个股用接口返回的名称。 */
    private static String labelFor(String code, String fallback) {
        String label = INDEX_LABELS.get(code);
        if (label != null) return label;
        String name = fallback == null ? "" : fallback.trim();
        return name.isEmpty() ? code.toUpperCase() : name;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException | NullPointerException ignored) {
            return 0;
        }
    }

    private static double number(Object value) {
        if (value instanceof Double number) return number;
        if (value instanceof String text) return number(text);
        return 0;
    }
}
