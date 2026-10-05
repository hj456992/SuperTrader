package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.util.PGobject;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

final class ProductionDatabase {
    interface SqlWork<T> { T run(Connection c) throws Exception; }
    private final String url,user,password,schema;
    ProductionDatabase(String url,String user,String password,String schema) {
        if(url==null || !url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL JDBC URL required");
        if(schema==null || !schema.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid database schema");
        this.url=url;this.user=user;this.password=password;this.schema=schema;
    }
    static ProductionDatabase fromEnvironment() {
        String url=System.getenv("EXPERT_DB_URL");
        return url==null || url.isBlank()?null:new ProductionDatabase(url,System.getenv("EXPERT_DB_USER"),System.getenv("EXPERT_DB_PASSWORD"),System.getenv().getOrDefault("EXPERT_DB_SCHEMA","expert_production"));
    }
    private Connection connect() throws SQLException {
        Properties p=new Properties();if(user!=null)p.setProperty("user",user);if(password!=null)p.setProperty("password",password);
        p.setProperty("stringtype","unspecified");p.setProperty("connectTimeout","10");p.setProperty("socketTimeout","30");
        // DSH loads plugins in isolated class loaders; JDBC SPI discovery belongs to the host.
        Connection c=new org.postgresql.Driver().connect(url,p);
        if(c==null)throw new SQLException("PostgreSQL URL was not accepted");
        try(var st=c.createStatement()){st.execute("SET search_path TO "+schema+", public");}catch(SQLException e){c.close();throw e;}
        return c;
    }
    void migrate() throws Exception {
        transaction(c->{
            try(var ps=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){ps.setString(1,"expert-production-schema:"+schema);ps.execute();}
            execute(c,"CREATE SCHEMA IF NOT EXISTS "+schema);
            int count=one(c,"SELECT count(*) AS n FROM information_schema.tables WHERE table_schema=? AND table_name LIKE 'ep_%'",schema).path("n").asInt();
            if(count==15) return null;
            if(count!=0)throw new SQLException("Incomplete expert production schema; migration required");
            String ddl;
            try(var in=ProductionDatabase.class.getResourceAsStream("/production-schema.sql")) {
                if(in==null)throw new IllegalStateException("Missing production-schema.sql");
                ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            ddl=ddl.replaceAll("(?m)^--.*$", "").replaceAll("(?m)^BEGIN;|^COMMIT;", "");
            try(var st=c.createStatement()){st.execute(ddl);}return null;
        });
    }
    <T>T transaction(SqlWork<T> action) throws Exception {return run(action,false);}
    <T>T read(SqlWork<T> action) throws Exception {return run(action,true);}
    private <T>T run(SqlWork<T> action,boolean read) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);c.setReadOnly(read);
            if(read)c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {T value=action.run(c);c.commit();return value;}catch(Exception|Error e){try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
        }
    }
    <T>T locked(String buildId,SqlWork<T> action) throws Exception {
        return transaction(c->{if(one(c,"SELECT id FROM ep_build WHERE id=? FOR UPDATE",buildId)==null)throw new IllegalArgumentException("Unknown build");return action.run(c);});
    }
    static int execute(Connection c,String sql,Object...args) throws Exception {
        try(var p=c.prepareStatement(sql)){bind(p,args);return p.executeUpdate();}
    }
    static List<ObjectNode> query(Connection c,String sql,Object...args) throws Exception {
        try(var p=c.prepareStatement(sql)){bind(p,args);try(var rs=p.executeQuery()){
            List<ObjectNode> result=new ArrayList<>();var md=rs.getMetaData();
            while(rs.next()){
                ObjectNode row=Json.object();for(int i=1;i<=md.getColumnCount();i++){
                    String key=md.getColumnLabel(i);Object v=rs.getObject(i);
                    if(v==null)row.putNull(key);
                    else if(v instanceof PGobject pg && ("jsonb".equals(pg.getType())||"json".equals(pg.getType())))row.set(key,Json.MAPPER.readTree(pg.getValue()));
                    else if(v instanceof UUID)row.put(key,v.toString());
                    else if(v instanceof Timestamp ts)row.put(key,ts.toInstant().toString());
                    else if(v instanceof java.time.OffsetDateTime ts)row.put(key,ts.toInstant().toString());
                    else row.set(key,Json.MAPPER.valueToTree(v));
                }result.add(row);
            }return result;
        }}
    }
    static ObjectNode one(Connection c,String sql,Object...args) throws Exception {var rows=query(c,sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static void bind(PreparedStatement p,Object[]args) throws SQLException {
        for(int i=0;i<args.length;i++) {Object v=args[i];if(v instanceof JsonNode node){PGobject json=new PGobject();json.setType("jsonb");json.setValue(node.toString());p.setObject(i+1,json);}else p.setObject(i+1,v);}
    }
}
