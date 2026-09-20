package com.calendar.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 网络请求的统一出口。
 *
 * <p>把行情、要闻、历史事件三个数据源共用的部分收在这里，避免每个 Service 各写一遍
 * 超时、请求头、编码处理——这三件事任何一处写漏都会变成难查的偶发问题。
 *
 * <p>几个实测得出的硬性要求：
 * <ul>
 *   <li>新浪 {@code hq.sinajs.cn} 不带 {@code Referer} 直接返回 403，必须带；</li>
 *   <li>新浪行情是 <b>GBK</b> 编码，用 UTF-8 解会把股票名解成乱码；</li>
 *   <li>部分接口对没有 {@code User-Agent} 的请求直接断连，报
 *       "HTTP/1.1 header parser received no bytes"。</li>
 * </ul>
 *
 * <p>超时故意设得比较短：这些都是"锦上添花"的辅助信息，宁可这次不显示，
 * 也不能让用户对着一个卡住的挂件等。失败一律抛 {@link IOException}，
 * 由调用方决定降级策略。
 *
 * <p>可用 {@code -Ddesktopcalendar.proxy=host:port} 指定代理。默认直连——
 * Java 的 HttpClient 不会自动读取系统代理设置，需要时只能显式配置。
 */
public final class Net {

    /** 连接超时：DNS + TCP 握手。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 读取超时：发出请求到收到完整响应体。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** GBK 供新浪行情使用。JDK 自带该字符集，无需额外依赖。 */
    public static final Charset GBK = Charset.forName("GBK");

    private static final HttpClient CLIENT = buildClient();

    private Net() { }

    private static HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = System.getProperty("desktopcalendar.proxy", "").trim();
        if (!proxy.isEmpty()) {
            String[] parts = proxy.split(":");
            if (parts.length == 2) {
                try {
                    builder.proxy(ProxySelector.of(
                            new InetSocketAddress(parts[0], Integer.parseInt(parts[1].trim()))));
                } catch (NumberFormatException ignored) {
                    // 端口写错就退回直连，不因为一个可选配置让整个应用起不来。
                }
            }
        }
        return builder.build();
    }

    /**
     * 发起 GET 请求并按指定编码返回响应体。
     *
     * @param url     完整地址
     * @param referer 部分站点要求携带的来源页；不需要时传 {@code null}
     * @param charset 响应编码，新浪行情必须传 {@link #GBK}
     * @throws IOException 网络失败、超时或非 2xx 响应
     */
    public static String get(String url, String referer, Charset charset) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET();
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", referer);
        }

        HttpResponse<byte[]> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            // 恢复中断标记：吞掉中断会让上层失去"该收手了"的信号。
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", exception);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }
        return new String(response.body(), charset);
    }

    /** 以 UTF-8 发起 GET。 */
    public static String get(String url, String referer) throws IOException {
        return get(url, referer, StandardCharsets.UTF_8);
    }
}
