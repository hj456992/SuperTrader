package dev.garden;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.Properties;
import java.util.function.Consumer;

/** Isolated documents; legacy conversation storage is never migrated or rewritten. */
final class RanchStore implements RanchRepository {
    RanchStore() throws Exception {
        try(var c=connect();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS garden_ranch_documents (id text PRIMARY KEY, document jsonb NOT NULL)");
        }
        var initial=Store.JSON.createObjectNode().put("revision",0);
        initial.putArray("people");initial.putArray("library");
        var self=initial.putObject("self").put("name","我").put("about","").put("style","").put("boundaries","");
        self.putArray("materials");self.putNull("profile");
        try(var c=connect();var q=c.prepareStatement("INSERT INTO garden_ranch_documents VALUES ('state',?::jsonb) ON CONFLICT DO NOTHING")) {
            q.setString(1,initial.toString());q.executeUpdate();
        }
    }
    private Connection connect() throws Exception {
        var p=new Properties();p.setProperty("user",System.getenv("GARDEN_DB_USER"));p.setProperty("password",System.getenv("GARDEN_DB_PASSWORD"));
        p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","15");
        return new org.postgresql.Driver().connect(System.getenv("GARDEN_DB_URL"),p);
    }
    public synchronized ObjectNode read() throws Exception {return get("state");}
    private ObjectNode get(String id) throws Exception {
        try(var c=connect();var q=c.prepareStatement("SELECT document::text FROM garden_ranch_documents WHERE id=?")) {
            q.setString(1,id);try(var rows=q.executeQuery()) {
                if(!rows.next())throw new IllegalArgumentException("资料已不存在，请刷新后重试。");
                return (ObjectNode)Store.JSON.readTree(rows.getString(1));
            }
        }
    }
    public synchronized ObjectNode update(long expected,Consumer<ObjectNode> mutation) throws Exception {
        var state=read();
        if(state.path("revision").asLong()!=expected)throw new IllegalStateException("资料已更新，请刷新后重试。");
        mutation.accept(state);state.put("revision",expected+1);
        try(var c=connect();var q=c.prepareStatement("UPDATE garden_ranch_documents SET document=?::jsonb WHERE id='state' AND (document->>'revision')::bigint=?")) {
            q.setString(1,state.toString());q.setLong(2,expected);
            if(q.executeUpdate()!=1)throw new IllegalStateException("资料已更新，本次旧结果未保存。");
        }
        return state;
    }
    public synchronized void putBook(String id,ObjectNode book) throws Exception {
        try(var c=connect();var q=c.prepareStatement("INSERT INTO garden_ranch_documents VALUES (?,?::jsonb)")) {
            q.setString(1,"book:"+id);q.setString(2,book.toString());q.executeUpdate();
        }
    }
    public synchronized ObjectNode book(String id) throws Exception {return get("book:"+id);}
    public synchronized void deleteBook(String id) throws Exception {
        try(var c=connect();var q=c.prepareStatement("DELETE FROM garden_ranch_documents WHERE id=?")) {q.setString(1,"book:"+id);q.executeUpdate();}
    }
    /** Runtime progress has its own document and never increments business revision. */
    public synchronized ObjectNode readJob() throws Exception {
        try(var c=connect();var q=c.prepareStatement("SELECT document::text FROM garden_ranch_documents WHERE id='profile-job'")) {
            try(var rows=q.executeQuery()){return rows.next()?(ObjectNode)Store.JSON.readTree(rows.getString(1)):Store.JSON.createObjectNode().put("status","idle");}
        }
    }
    public synchronized void writeJob(ObjectNode job) throws Exception {
        try(var c=connect();var q=c.prepareStatement("INSERT INTO garden_ranch_documents VALUES ('profile-job',?::jsonb) ON CONFLICT (id) DO UPDATE SET document=EXCLUDED.document")) {
            q.setString(1,job.toString());q.executeUpdate();
        }
    }
}
