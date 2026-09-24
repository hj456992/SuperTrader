package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** 人物上下文与反馈循环；每次事实更改都撤销派生分析。 */
final class GardenService {
    private final Store store;
    private final Analyzer analyzer;
    private final java.util.function.Supplier<?> feishu;
    private final CaptureCache captures = new CaptureCache();
    private final ChatChoices chatChoices = new ChatChoices();
    private final java.util.function.Function<Map<String,Object>,?> feishuBrowse;

    /** @param store 人物仓库。 @param analyzer 真实模型分析器。 */
    GardenService(Store store, Analyzer analyzer, java.util.function.Supplier<?> feishu, java.util.function.Function<Map<String,Object>,?> feishuBrowse) {
        this.feishuBrowse = feishuBrowse;
        this.feishu = feishu;
        this.store = store;
        this.analyzer = analyzer;
    }

    /** 页面恢复使用持久化状态，不依赖浏览器存储。 */
    Object state() throws Exception {
        var job = analyzer.status();
        var people = store.list();
        people.forEach(p -> p.set("assistantContext", AssistantState.describe(p)));
        return Map.of("people", people, "job", job, "model", Analyzer.MODEL,
            "modelConfigured", System.getenv("DEEPSEEK_API_KEY") != null, "backend", "dsh-java kernel + model plugins", "storage", "PostgreSQL · 独立数据库");
    }

    /** @param action 已鉴权的操作名称。 @param body 有界 JSON 请求。 */
    Object command(String action, JsonNode body) throws Exception {
        if ("feishu-chats".equals(action)) {
            return chatChoices.register(Store.JSON.valueToTree(feishuBrowse.apply(Map.of("action","list"))));
        }
        if ("feishu-select".equals(action)) {
            return captures.register(Store.JSON.valueToTree(feishuBrowse.apply(chatChoices.request(text(body,"choiceId",100)))));
        }
        if ("feishu-capture".equals(action)) {
            ObjectNode snapshot = Store.JSON.valueToTree(feishu.get());
            return captures.register(snapshot);
        }
        if ("create".equals(action)) {
            return create(body);
        }
        if ("cancel".equals(action)) {
            analyzer.cancel();
            return Map.of("ok", true);
        }
        var id = text(body, "id", 100);
        var person = store.get(id);
        // 同一导入操作的网络重试是幂等的，不把相同文字在不同交流中的出现吞掉。
        if ("append".equals(action) && person.path("importOperations").isArray()) {
            for (var operation : person.path("importOperations")) {
                if (operation.asText().equals(body.path("operationId").asText())) {
                    return person;
                }
            }
        }
        if (!body.path("revision").isIntegralNumber() || body.path("revision").asLong() != person.path("revision").asLong()) {
            throw new IllegalStateException("上下文已变化，请刷新页面。");
        }
        var revision = body.path("revision").asLong();
        switch (action) {
            case "assistant-settings":
                return store.update(id, revision, p -> AssistantState.configure(p, body));
            case "analyze":
                return analyzer.start(person.deepCopy());
            case "append":
                var incoming = parse(text(body, "text", 16000), "group".equals(AssistantState.describe(person).path("type").asText()));
                var operationId = text(body, "operationId", 100);
                return store.update(id, revision, p -> {
                    var messages = (ArrayNode) p.path("messages");
                    incoming.forEach(messages::add);
                    if (!p.path("importOperations").isArray()) {
                        p.putArray("importOperations");
                    }
                    ((ArrayNode) p.path("importOperations")).add(operationId);
                    if (messages.size() > 80 || messages.toString().length() > 24000) {
                        throw new IllegalArgumentException("初版最多保存80条、24000字上下文，请先删减记录。");
                    }
                    AssistantState.invalidate(p, false);
                });
            case "correct":
            case "remove-message":
                var messageId = text(body, "messageId", 100);
                return store.update(id, revision, p -> {
                    var messages = (ArrayNode) p.path("messages");
                    int index = -1;
                    for (int i = 0; i < messages.size(); i++) {
                        if (messageId.equals(messages.get(i).path("id").asText())) {
                            index = i;
                            break;
                        }
                    }
                    if (index < 0) {
                        throw new IllegalArgumentException("这条记录已不存在。");
                    }
                    if ("remove-message".equals(action)) {
                        messages.remove(index);
                    } else {
                        ((ObjectNode) messages.get(index)).put("text", text(body, "text", 4000));
                        ((ObjectNode) messages.get(index)).put("corrected", true);
                    }
                    if (messages.toString().length() > 24000) {
                        throw new IllegalArgumentException("纠正后总上下文过长，请缩短内容后保存。");
                    }
                    // 修正或删除使所有上一轮输入标记失效，重新从原文形成判断。
                    AssistantState.invalidate(p, true);
                });
            case "adopt":
                var draft = text(body, "text", 4000);
                if (person.path("analysis").isNull() || !person.path("analysis").path("id").asText().equals(text(body, "analysisId", 100))) {
                    throw new IllegalStateException("建议已失效，请重新分析。");
                }
                return store.update(id, revision, p -> {
                    var message = message("me", draft);
                    message.put("selfReportedSent", true);
                    var messages = (ArrayNode) p.path("messages");
                    messages.add(message);
                    if (messages.size() > 80 || messages.toString().length() > 24000) {
                        throw new IllegalArgumentException("当前上下文已达到初版容量，请先删减旧记录。");
                    }
                    AssistantState.invalidate(p, false);
                });
            case "delete":
                store.delete(id, revision);
                return Map.of("ok", true);
            default:
                throw new IllegalArgumentException("不支持的操作。");
        }
    }

    /** @param body 用户确认的人物身份与导入文本。 */
    private ObjectNode create(JsonNode body) throws Exception {
        var person = Store.JSON.createObjectNode();
        person.put("id", UUID.randomUUID().toString());
        var group = text(body, "group", 100);
        var platform = body.path("platform").asText("wechat");
        if (!List.of("wechat", "feishu").contains(platform)) {
            throw new IllegalArgumentException("请选择微信或飞书。");
        }
        person.put("name", body.has("name") ? text(body, "name", 60) : group);
        person.put("group", group);
        person.put("platform", platform);
        person.put("goal", text(body, "goal", 300));
        person.put("source", "sample".equals(body.path("source").asText()) ? "sample" : "imported");
        person.put("revision", 1);
        person.set("messages", parse(text(body, "text", 16000), "group".equals(body.path("conversationType").asText())));
        if (person.path("messages").toString().length() > 24000) {
            throw new IllegalArgumentException("上下文过长，请缩短后保存。");
        }
        person.putArray("importOperations");
        person.putNull("analysis");
        captures.apply(body, person);
        var settings = Store.JSON.createObjectNode().put("conversationType",body.path("conversationType").asText("auto")).put("selfId","");
        settings.putArray("focusIds");
        AssistantState.configure(person,settings);
        if (person.path("messages").toString().length() > 24000) throw new IllegalArgumentException("记录连同来源信息超过容量，请减少内容后保存。");
        store.create(person);
        return person;
    }

    /** 严格按角色前缀解析，避免把用户或群友的话误记为对方。
     * @param input 每行以“对方：”“我：”“备注：”开头的文本。
     */
    private ArrayNode parse(String input, boolean group) {
        var result = Store.JSON.createArrayNode();
        for (var line : input.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var parts = line.strip().split("[：:]", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[0].length()>100 || (!group && !List.of("对方", "我", "备注").contains(parts[0])) || parts[1].isBlank()) {
                throw new IllegalArgumentException("每行请以 对方：、我： 或 备注： 开头，后面写原话。");
            }
            if (parts[1].length() > 4000 || result.size() >= 60) {
                throw new IllegalArgumentException("一次最多60条，每条最多4000字。");
            }
            var role = switch (parts[0]) {
                case "我" -> "me";
                case "备注" -> "note";
                default -> "them";
            };
            var item = message(role, parts[1].strip());
            if (group && !List.of("对方","我","备注").contains(parts[0])) {
                item.put("sender",parts[0].strip()).put("source","manual_group");
            }
            result.add(item);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("请至少加入一条记录。");
        }
        return result;
    }

    /** @param role 明确的消息归属。 @param text 用户提供的原话。 */
    private ObjectNode message(String role, String text) {
        var message = Store.JSON.createObjectNode();
        message.put("id", "E-" + UUID.randomUUID().toString().substring(0, 8));
        message.put("role", role);
        message.put("text", text);
        message.put("importedAt", Instant.now().toString());
        return message;
    }

    /** @param node 输入对象。 @param key 字段名。 @param max 最大字符数。 */
    static String text(JsonNode node, String key, int max) {
        var value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            throw new IllegalArgumentException("请检查输入字段：" + key);
        }
        return value.asText().strip();
    }
}
