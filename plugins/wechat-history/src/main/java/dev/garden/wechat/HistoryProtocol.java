package dev.garden.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashSet;

/** 在 HTTP 提交之前验证 CLI 的版本、唯一目标、身份和完整逐条记录结构。 */
final class HistoryProtocol {
    private HistoryProtocol() {}

    /** 返回同一经过验证的批次，保持 CLI 原始字段和整数精度。 */
    static JsonNode validate(JsonNode batch, JsonNode previous) throws IOException {
        require(batch != null && batch.isObject());
        require("aichat.history.v1".equals(batch.path("schema").asText()));
        require(HistoryConfig.TARGET.equals(batch.path("target").asText()));
        String account = nonblank(batch, "account_fingerprint");
        String chat = nonblank(batch, "chat_id");
        require(chat.endsWith("@chatroom"));
        JsonNode checkpoint = batch.path("checkpoint");
        validateCheckpoint(checkpoint);
        require(account.equals(checkpoint.path("account").asText()));
        require(chat.equals(checkpoint.path("chat").asText()));
        if (previous != null && !previous.isNull()) {
            validateCheckpoint(previous);
            require(account.equals(previous.path("account").asText()));
            require(chat.equals(previous.path("chat").asText()));
        }
        require(batch.path("has_more").isBoolean());
        require(batch.path("rows").isArray() && batch.path("rows").size() <= 200);
        var seen = new HashSet<String>();
        for (JsonNode row : batch.path("rows")) {
            require(row.isObject());
            String database = nonblank(row, "database");
            require(row.path("local_id").isIntegralNumber() && row.path("local_id").canConvertToLong());
            long localId = row.path("local_id").longValue();
            require(localId > 0 && seen.add(database + "\0" + localId));
            JsonNode cursor = checkpoint.path("cursor").path(database);
            require(cursor.isIntegralNumber() && localId <= cursor.longValue());
            JsonNode server = row.path("server_id");
            require(server.isIntegralNumber() || server.isTextual() && server.asText().matches("[0-9]+"));
            require(row.has("sender_id") && (row.path("sender_id").isNull() || row.path("sender_id").isTextual()));
            require(row.path("author").isTextual());
            require(row.has("text") && (row.path("text").isNull() || row.path("text").isTextual()));
            require(row.path("create_time").isIntegralNumber() && row.path("create_time").canConvertToLong()
                    && row.path("create_time").longValue() >= 0);
            require(row.path("local_type").isIntegralNumber());
        }
        return batch;
    }

    /** 已提交检查点也必须具有准确身份及逐数据库的整数游标。 */
    static void validateCheckpoint(JsonNode checkpoint) throws IOException {
        require(checkpoint.isObject());
        nonblank(checkpoint, "account");
        require(nonblank(checkpoint, "chat").endsWith("@chatroom"));
        require(checkpoint.path("cursor").isObject() && checkpoint.path("salts").isObject());
        var fields = checkpoint.path("cursor").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            require(!field.getKey().isBlank() && field.getValue().isIntegralNumber()
                    && field.getValue().canConvertToLong() && field.getValue().longValue() >= 0);
            JsonNode salt = checkpoint.path("salts").path(field.getKey());
            require(salt.isTextual() && salt.asText().matches("[0-9a-fA-F]{32}"));
        }
    }

    private static String nonblank(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.asText().isBlank());
        return value.asText();
    }

    private static void require(boolean valid) throws IOException {
        if (!valid) throw new IOException("invalid_history_protocol");
    }
}
