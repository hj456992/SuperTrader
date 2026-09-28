package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** 保留原始页码与版本的分段、检索和来源验证。 */
final class Knowledge {
    /** 按页和段落切分，长段仍保留全部字符。@param doc 文档身份 @param version 版本身份 @param title 资料名称 @param pages 解析页 */
    static ArrayNode chunks(String doc, String version, String title, ArrayNode pages) {
        ArrayNode result = Json.array();
        for (JsonNode page : pages) {
            String text = page.path("text").asText().strip();
            int start = 0, part = 0;
            while (start < text.length()) {
                int end = Math.min(start + 2400, text.length());
                if (end < text.length()) {
                    int boundary = text.lastIndexOf('\n', end);
                    if (boundary > start + 1200) { end = boundary + 1; }
                    if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) { end--; }
                }
                String content = text.substring(start, end).strip();
                if (!content.isEmpty()) {
                    result.add(Json.object().put("id", doc + ":" + version + ":p" + page.path("page").asInt() + ":" + (++part))
                        .put("documentId", doc).put("versionId", version).put("title", title).put("page", page.path("page").asInt()).put("text", content));
                }
                start = end;
            }
        }
        return result;
    }
    /** 返回实际存在的来源身份集合。@param chunks 原文片段 */
    static Set<String> ids(ArrayNode chunks) {
        Set<String> result = new HashSet<>();
        chunks.forEach(c -> result.add(c.path("id").asText()));
        return result;
    }
    /** 拒绝无来源的方法，来源必须来自本次读到的原文。@param methods 方法候选 @param chunks 已读原文 */
    static void validateMethods(ArrayNode methods, ArrayNode chunks) {
        if (methods.isEmpty() || methods.size() > 60) { throw new IllegalArgumentException("方法数量无效"); }
        Set<String> allowed = ids(chunks);
        for (JsonNode method : methods) {
            for (String field : List.of("title", "when", "steps", "limits")) { Json.required(method, field, 2400); }
            JsonNode refs = method.path("sourceIds");
            if (!refs.isArray() || refs.isEmpty() || refs.size() > 12) { throw new IllegalArgumentException("方法缺少有效原文依据"); }
            for (JsonNode ref : refs) {
                if (!allowed.contains(ref.asText())) { throw new IllegalArgumentException("模型引用了本批次以外的资料"); }
            }
        }
    }
    /** 按指定身份收集片段，不接受范围以外的标识。@param chunks 可见片段 @param requested 请求身份 */
    static ArrayNode restrict(ArrayNode chunks, Collection<String> requested) {
        ArrayNode result = Json.array();
        Set<String> wanted = new HashSet<>(requested);
        for (JsonNode c : chunks) { if (wanted.remove(c.path("id").asText())) { result.add(c.deepCopy()); } }
        if (!wanted.isEmpty()) { throw new IllegalArgumentException("资料来源不属于当前专家版本"); }
        return result;
    }
    /** 简单中英文词项检索，明确不把零命中伪装成相关结果。@param chunks 可见原文 @param query 查询 @param limit 最大条数 */
    static ArrayNode search(ArrayNode chunks, String query, int limit) {
        Set<String> terms = new HashSet<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2) { terms.add(word); }
            for (int i = 0; i + 1 < word.length(); i++) { terms.add(word.substring(i, i + 2)); }
        }
        List<JsonNode> sorted = new ArrayList<>();
        chunks.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt((JsonNode c) -> score(c, terms)).reversed());
        ArrayNode result = Json.array();
        for (JsonNode c : sorted) {
            if (score(c, terms) > 0) { result.add(c.deepCopy()); }
            if (result.size() >= limit) { break; }
        }
        return result;
    }
    /** 根据出现的查询词计算简单相关性。@param chunk 原文 @param terms 查询词 */
    private static int score(JsonNode chunk, Set<String> terms) {
        String text = chunk.path("text").asText().toLowerCase(Locale.ROOT);
        return terms.stream().filter(text::contains).mapToInt(String::length).sum();
    }
}
