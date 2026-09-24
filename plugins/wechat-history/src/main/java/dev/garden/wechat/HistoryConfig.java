package dev.garden.wechat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 启动配置只接受明确路径和回环 HTTP 目的地，不从环境变量猜测账号。 */
record HistoryConfig(List<String> commandPrefix, Path cliConfig, Path keyFile, Path dataDir,
                     URI logbookUrl, Path tokenFile, int intervalSeconds, int timeoutSeconds) {
    static final String TARGET = "卧底不追高";

    /** 校验配置后复制命令数组，防止启动后的可变配置改变子进程参数。 */
    static HistoryConfig from(Map<?, ?> values) {
        if (!java.nio.charset.Charset.defaultCharset().equals(java.nio.charset.StandardCharsets.UTF_8)) {
            throw new IllegalArgumentException("Host JVM requires -Dfile.encoding=UTF-8 for Chinese process arguments");
        }
        Object command = values.get("commandPrefix");
        if (!(command instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.contains("\0"))) {
            throw new IllegalArgumentException("commandPrefix must be a nonempty string array");
        }
        List<String> prefix = list.stream().map(String.class::cast).toList();
        if (!Path.of(prefix.get(0)).isAbsolute()) {
            throw new IllegalArgumentException("commandPrefix executable must be absolute");
        }
        URI base = URI.create(string(values, "logbookUrl"));
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("logbookUrl must be an http://127.0.0.1 base URL");
        }
        return new HistoryConfig(prefix, path(values, "cliConfig"), path(values, "keyFile"),
                path(values, "dataDir"), base, path(values, "tokenFile"),
                seconds(values, "intervalSeconds", 3), seconds(values, "timeoutSeconds", 30));
    }

    private static String string(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + name);
        }
        return s;
    }

    private static Path path(Map<?, ?> values, String name) {
        Path path = Path.of(string(values, name));
        if (!path.isAbsolute()) throw new IllegalArgumentException(name + " must be absolute");
        return path.normalize();
    }

    private static int seconds(Map<?, ?> values, String name, int fallback) {
        if (!values.containsKey(name)) return fallback;
        Object value = values.get(name);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 1 || number.intValue() > 3600) {
            throw new IllegalArgumentException(name + " must be an integer from 1 to 3600");
        }
        return number.intValue();
    }
}
