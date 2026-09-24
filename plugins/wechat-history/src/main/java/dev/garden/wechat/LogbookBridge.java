package dev.garden.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Files;

/** 只向本机日志服务发送带 native token 的请求；不跟随重定向或打印请求正文。 */
final class LogbookBridge implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private final HistoryConfig config;
    private volatile HttpURLConnection active;
    private volatile boolean closed;

    LogbookBridge(HistoryConfig config) { this.config = config; }

    /** 每次请求重读小型 token 文件，允许宿主安全轮换 token。 */
    JsonNode request(String endpoint, JsonNode payload) throws Exception {
        if (closed) throw new IOException("closed");
        if (Files.size(config.tokenFile()) > 8192) throw new IOException("invalid_token");
        String token = Files.readString(config.tokenFile()).strip();
        if (token.isBlank() || token.contains("\n") || token.contains("\r")) throw new IOException("invalid_token");
        var connection = (HttpURLConnection) config.logbookUrl().resolve(endpoint).toURL().openConnection(java.net.Proxy.NO_PROXY);
        active = connection;
        try {
            if (closed) throw new IOException("closed");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("X-Logbook-Token", token);
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                byte[] body = JSON.writeValueAsBytes(payload);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("logbook_http_failed");
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(1024 * 1024 + 1);
                if (body.length > 1024 * 1024) throw new IOException("logbook_response_limit");
                return body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            }
        } finally {
            connection.disconnect();
            active = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
