package com.paicli.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;

/**
 * Strict reader for the JSON-only replies of the Team planner and reviewer.
 *
 * <p>The reply must be exactly one JSON object. A single Markdown fence wrapping the whole reply
 * is the only tolerated decoration; fences inside the payload are left untouched, duplicate keys
 * and trailing text are rejected. Callers decide what a rejection means (the reviewer treats it
 * as "not approved", the planner as "no plan").
 * 中文：严格读取团队规划者和审阅者的纯 JSON 回复。回复必须恰为一个 JSON 对象，只允许整个回复外包一层 Markdown
 * 围栏；载荷内部围栏不动，拒绝重复键和尾随文本。审阅者将拒绝视为未批准，规划者视为无计划。
 */
final class TeamStructuredReply {

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String FENCE = "```";

    private TeamStructuredReply() {
    }

    static JsonNode parseObject(String reply) throws IOException {
        if (reply == null || reply.isBlank()) {
            throw new IOException("回复为空");
        }
        JsonNode root;
        try {
            root = STRICT_MAPPER.readTree(unwrapWholeFence(reply.trim()));
        } catch (IOException e) {
            throw new IOException("回复不是单个合法 JSON 对象", e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("回复必须是 JSON 对象");
        }
        return root;
    }

    /** Removes one fence only when it encloses the entire reply, e.g. ```json ... ```. 中文：仅在围栏包住整个回复时移除一层，例如 json 围栏。 */
    private static String unwrapWholeFence(String text) throws IOException {
        if (!text.startsWith(FENCE)) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0 || !text.endsWith(FENCE)) {
            throw new IOException("代码块围栏不完整");
        }
        String language = text.substring(FENCE.length(), firstLineEnd).trim().toLowerCase(Locale.ROOT);
        if (!language.isEmpty() && !language.equals("json")) {
            throw new IOException("代码块语言必须为 json");
        }
        return text.substring(firstLineEnd + 1, text.length() - FENCE.length()).trim();
    }
}
