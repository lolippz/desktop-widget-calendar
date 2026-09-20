package com.calendar.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器。
 *
 * <p>为什么不引第三方库：这个工程的外部依赖只有 flatlaf / lunar / sqlite-jdbc 三个，
 * 而行情与新闻接口用到的 JSON 结构都很浅（对象套数组、数组里是对象）。
 * 为了这点需求加一个 JSON 依赖，用户就得改 pom 重新下载，收益不抵成本。
 *
 * <p>解析结果用最朴素的类型表示：对象是 {@code Map<String,Object>}，数组是
 * {@code List<Object>}，字符串是 {@code String}，数字是 {@code Double}，
 * 布尔是 {@code Boolean}，null 就是 {@code null}。取值一律走本类的静态辅助方法，
 * 这样调用方不必到处写强制类型转换，也避免了"某个字段偶尔是字符串、偶尔是数字"
 * 这类接口不稳定带来的 ClassCastException。
 *
 * <p>只做解析，不做序列化——本工程没有需要输出 JSON 的场景。
 */
public final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    /**
     * 解析一个 JSON 文本。
     *
     * @return 对象 / 数组 / 字符串 / Double / Boolean / null
     * @throws IllegalArgumentException 文本不是合法 JSON 时抛出
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("待解析的 JSON 为空");
        }
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        return value;
    }

    // ------------------------------------------------------------ 取值辅助

    /** 把节点当作对象取回；不是对象时返回空 Map，调用方无需判空。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : Map.of();
    }

    /** 取出对象里的某个字段并当作对象；路径不存在时返回空 Map。 */
    public static Map<String, Object> object(Object node, String key) {
        return object(field(node, key));
    }

    /** 把节点当作数组取回；不是数组时返回空 List。 */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object node) {
        return node instanceof List ? (List<Object>) node : List.of();
    }

    /** 取出对象里的某个字段并当作数组；路径不存在时返回空 List。 */
    public static List<Object> array(Object node, String key) {
        return array(field(node, key));
    }

    /**
     * 取出字符串字段。
     *
     * <p>接口里同一个字段有时给数字有时给字符串（价格尤其常见），所以这里
     * 对数字也做一次转换，避免调用方为了一个字段写两套分支。
     */
    public static String string(Object node, String key) {
        Object value = field(node, key);
        if (value == null) return "";
        if (value instanceof Double number) {
            return trimNumber(number);
        }
        return value.toString();
    }

    /** 取出数值字段；缺失或无法解析时返回 {@code fallback}。 */
    public static double number(Object node, String key, double fallback) {
        Object value = field(node, key);
        if (value instanceof Double number) return number;
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 取字段的原始节点。 */
    public static Object field(Object node, String key) {
        if (!(node instanceof Map)) return null;
        return object(node).get(key);
    }

    /** Double 去掉多余的 ".0"，让 "8" 不会显示成 "8.0"。 */
    private static String trimNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------ 递归下降

    private Object readValue() {
        skipWhitespace();
        if (index >= text.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char current = text.charAt(index);
        switch (current) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        index++; // 跳过 '{'
        skipWhitespace();
        if (index < text.length() && text.charAt(index) == '}') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != ':') {
                throw new IllegalArgumentException("对象缺少冒号，位置 " + index);
            }
            index++;
            result.put(key, readValue());
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("对象未闭合");
            }
            char next = text.charAt(index++);
            if (next == '}') {
                return result;
            }
            if (next != ',') {
                throw new IllegalArgumentException("对象分隔符非法：" + next + "，位置 " + (index - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        index++; // 跳过 '['
        skipWhitespace();
        if (index < text.length() && text.charAt(index) == ']') {
            index++;
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("数组未闭合");
            }
            char next = text.charAt(index++);
            if (next == ']') {
                return result;
            }
            if (next != ',') {
                throw new IllegalArgumentException("数组分隔符非法：" + next + "，位置 " + (index - 1));
            }
        }
    }

    private String readString() {
        if (index >= text.length() || text.charAt(index) != '"') {
            throw new IllegalArgumentException("期望字符串，位置 " + index);
        }
        index++;
        StringBuilder builder = new StringBuilder();
        while (index < text.length()) {
            char current = text.charAt(index++);
            if (current == '"') {
                return builder.toString();
            }
            if (current != '\\') {
                builder.append(current);
                continue;
            }
            if (index >= text.length()) break;
            char escaped = text.charAt(index++);
            switch (escaped) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (index + 4 > text.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整，位置 " + index);
                    }
                    String hex = text.substring(index, index + 4);
                    index += 4;
                    builder.append((char) Integer.parseInt(hex, 16));
                }
                default -> builder.append(escaped);
            }
        }
        throw new IllegalArgumentException("字符串未闭合");
    }

    private Double readNumber() {
        int start = index;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '-' || current == '+' || current == '.' || current == 'e' || current == 'E'
                    || (current >= '0' && current <= '9')) {
                index++;
            } else {
                break;
            }
        }
        if (start == index) {
            throw new IllegalArgumentException("非法数值，位置 " + index);
        }
        return Double.parseDouble(text.substring(start, index));
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("期望 " + literal + "，位置 " + index);
        }
        index += literal.length();
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
