package dev.ailiao.expert;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.UUID;

/** 统一 JSON 创建与输入校验，避免把模型输出直接当成可信配置。 */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 创建空对象。 */
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    /** 创建空数组。 */
    static ArrayNode array() { return MAPPER.createArrayNode(); }
    /** 创建无路径含义的内部身份。 */
    static String id() { return UUID.randomUUID().toString(); }
    /** 读取必填短文本。@param node 输入对象 @param key 字段名 @param max 最大长度 */
    static String required(JsonNode node, String key, int max) {
        String value = node.path(key).asText("").trim();
        if (value.isEmpty() || value.length() > max) { throw new IllegalArgumentException(key + " 不能为空，且不得超过 " + max + " 字"); }
        return value;
    }
    /** 只接受完整 JSON 对象，允许模型包裹 Markdown 代码围栏。@param text 模型文本 */
    static ObjectNode parse(String text) throws Exception {
        JsonNode value = MAPPER.readTree(text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", ""));
        if (!(value instanceof ObjectNode result)) { throw new IllegalArgumentException("模型未返回完整 JSON 对象"); }
        return result;
    }
}
