package dev.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.*;
import java.util.function.Consumer;

/** 独立 PostgreSQL 人物聚合；版本比较阻止迟到的模型结果覆盖新上下文。 */
final class Store {
    static final ObjectMapper JSON = new ObjectMapper();

    /** 建表只作用于启动脚本创建的独立数据库。 */
    Store() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS garden_person (id text PRIMARY KEY, revision bigint NOT NULL, document jsonb NOT NULL)");
        }
    }

    /** 从环境建立短连接；不保存或打印凭据。 */
    private Connection connect() throws Exception {
        // 独立插件类加载器直接使用驱动实例，避免 DriverManager 可见性差异。
        var properties = new Properties();
        properties.setProperty("user", System.getenv("GARDEN_DB_USER"));
        properties.setProperty("password", System.getenv("GARDEN_DB_PASSWORD"));
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "10");
        return new org.postgresql.Driver().connect(System.getenv("GARDEN_DB_URL"), properties);
    }

    /** 列出人物；返回反序列化副本防止异步任务修改共享对象。 */
    synchronized List<ObjectNode> list() throws Exception {
        var result = new ArrayList<ObjectNode>();
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT document::text FROM garden_person ORDER BY id")) {
            while (rows.next()) {
                result.add((ObjectNode) JSON.readTree(rows.getString(1)));
            }
        }
        return result;
    }

    /** @param id 本地人物稳定标识。 */
    synchronized ObjectNode get(String id) throws Exception {
        try (var connection = connect(); var query = connection.prepareStatement("SELECT document::text FROM garden_person WHERE id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("找不到这个人物，请刷新页面。");
                }
                return (ObjectNode) JSON.readTree(rows.getString(1));
            }
        }
    }

    /** @param person 新人物；相同名字仍以不同 UUID 隔离。 */
    synchronized void create(ObjectNode person) throws Exception {
        try (var connection = connect(); var query = connection.prepareStatement("INSERT INTO garden_person VALUES (?, ?, ?::jsonb)")) {
            query.setString(1, person.path("id").asText());
            query.setLong(2, person.path("revision").asLong());
            query.setString(3, person.toString());
            query.executeUpdate();
        }
    }

    /** 原子修改聚合。
     * @param id 人物标识。
     * @param expected 客户端或模型读取的版本。
     * @param mutation 只修改本次加载的副本。
     */
    synchronized ObjectNode update(String id, long expected, Consumer<ObjectNode> mutation) throws Exception {
        var person = get(id);
        if (person.path("revision").asLong() != expected) {
            throw new IllegalStateException("上下文已更新，请刷新后再操作。");
        }
        mutation.accept(person);
        person.put("revision", expected + 1);
        try (var connection = connect(); var query = connection.prepareStatement("UPDATE garden_person SET revision=?, document=?::jsonb WHERE id=? AND revision=?")) {
            query.setLong(1, expected + 1);
            query.setString(2, person.toString());
            query.setString(3, id);
            query.setLong(4, expected);
            if (query.executeUpdate() != 1) {
                throw new IllegalStateException("上下文已更新，旧结果没有保存。");
            }
        }
        return person;
    }

    /** @param id 要删除的人物标识。 @param expected 页面读取的版本。 */
    synchronized void delete(String id, long expected) throws Exception {
        try (var connection = connect(); var query = connection.prepareStatement("DELETE FROM garden_person WHERE id=? AND revision=?")) {
            query.setString(1, id);
            query.setLong(2, expected);
            if (query.executeUpdate() != 1) {
                throw new IllegalStateException("上下文已更新，请刷新后再删除。");
            }
        }
    }
}
