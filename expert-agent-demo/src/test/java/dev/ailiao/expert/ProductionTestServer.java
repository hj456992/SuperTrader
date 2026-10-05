package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** TEST ONLY: actual PG/service/HTTP, fictional legacy source, no provider adapter. */
final class ProductionTestServer implements AutoCloseable {
    final ProductionDatabase db;
    final LabStore legacy;
    final ObjectNode document;
    final ControlledModel model;
    final ProductionRedis redis;
    final Path root;
    final String schema;
    final String token = UUID.randomUUID().toString();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final List<ProductionService> services = new ArrayList<>();
    final List<HttpServer> servers = new ArrayList<>();
    ProductionService service;
    URI base;
    private final String url, user, password;

    ProductionTestServer(Path root) throws Exception { this(root,new ControlledModel()); }
    ProductionTestServer(Path root, ControlledModel model) throws Exception {this(root,model,new ProductionRedis(null,null));}
    ProductionTestServer(Path root, ControlledModel model, ProductionRedis redis) throws Exception {
        this.root=root; this.model=model; this.redis=redis;
        schema="ep_qa_"+UUID.randomUUID().toString().replace("-", "");
        url=required("EXPERT_DB_URL"); user=required("EXPERT_DB_USER");
        password=System.getenv().getOrDefault("EXPERT_DB_PASSWORD", "");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Explicit PostgreSQL test URL required");
        db=new ProductionDatabase(url,user,password,schema);
        db.migrate();
        legacy=new LabStore(root.resolve("legacy-fixture"));
        document=legacy.importDocument("原创验收教材","",Json.array()
            .add(Json.object().put("page",1).put("text","😀汇报先核实可验证事实，再说明条件和结论。例：工程延误两天，应说明证据与待确认依赖。"))
            .add(Json.object().put("page",2).put("text","倾听先复述并确认需求，再给方案；不能把猜测当事实。例：先问希望获得信息还是协助。")),
            "fictional-acceptance.pdf","%PDF-1.4\n% test-only fictional source fixture\n%%EOF".getBytes(StandardCharsets.UTF_8));
        service=newService(); base=serve(service);
    }
    ProductionService newService() throws Exception {
        ProductionService value=new ProductionService(db,model,legacy,redis,root.resolve("shared-fixture"));
        services.add(value); return value;
    }
    URI serve(ProductionService value) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ProductionHttp http=new ProductionHttp(value);
        server.createContext("/",exchange->{
            try {
                String host=exchange.getRequestHeaders().getFirst("Host");
                boolean write=!exchange.getRequestMethod().equals("GET");
                if(!("127.0.0.1:"+server.getAddress().getPort()).equals(host) ||
                    (write&&!token.equals(exchange.getRequestHeaders().getFirst("X-Lab-Token")))) {
                    exchange.sendResponseHeaders(403,-1); return;
                }
                if(!http.handle(exchange)) exchange.sendResponseHeaders(404,-1);
            } finally { exchange.close(); }
        });
        server.start(); servers.add(server);
        return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/expert-production/v1");
    }
    ObjectNode createRequest() {
        ObjectNode request=Json.object().put("teamId",UUID.randomUUID().toString())
            .put("teamName","验收用测试团队").put("name","验收专家").put("responsibility","协助有依据的表达与倾听");
        request.set("documents",Json.array().add(Json.object().put("documentId",document.path("documentId").asText())
            .put("documentVersionId",document.path("versionId").asText())));
        return request;
    }
    record Response(int status,ObjectNode body) {}
    Response request(String method,String path,ObjectNode data) throws Exception {return request(base,method,path,data);}
    Response request(URI endpoint,String method,String path,ObjectNode data) throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(10))
            .header("X-Lab-Token",token).header("Content-Type","application/json");
        builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data.toString()));
        HttpResponse<String> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        ObjectNode body=response.body().isBlank()?Json.object():Json.parse(response.body());
        return new Response(response.statusCode(),body);
    }
    ObjectNode snapshot(String build) throws Exception {
        Response result=request("GET","/builds/"+build+"/snapshot",null);
        if(result.status()!=200) {
            AssertionError failure=new AssertionError("snapshot status="+result.status()+" "+result.body());
            // Preserve the original HTTP failure. Diagnostic read must NEVER turn it into a pass.
            try { service.snapshot(build); }
            catch(Exception diagnostic) { failure.initCause(diagnostic); }
            throw failure;
        }
        return result.body();
    }
    static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank()) throw new IllegalStateException("Missing explicit integration configuration: "+name);
        return value;
    }
    @Override public void close() throws Exception {
        model.release();
        for(HttpServer server:servers) server.stop(0);
        for(ProductionService value:services) value.close();
        if(!schema.matches("ep_qa_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }

    /** Only the external semantic/model boundary is substituted; this is NOT model-quality evidence. */
    static final class ControlledModel implements ProductionModel {
        volatile String blockedPurpose="", blockedKind="";
        volatile boolean invalidSource, failGeneration, clarifyFirstAgent, maliciousApproval;
        volatile CountDownLatch entered=new CountDownLatch(1), proceed=new CountDownLatch(0);
        final List<ObjectNode> inputs=new CopyOnWriteArrayList<>();
        void block(String purpose,String kind) {blockedPurpose=purpose;blockedKind=kind;entered=new CountDownLatch(1);proceed=new CountDownLatch(1);}
        void release() {proceed.countDown();blockedPurpose="";}
        @Override public ObjectNode json(String purpose,ObjectNode input,BooleanSupplier cancelled) throws Exception {
            inputs.add(input.deepCopy().put("testPurpose",purpose));
            if(purpose.equals(blockedPurpose)&&(blockedKind.isBlank()||blockedKind.equals(input.path("kind").asText()))) {
                blockedPurpose=""; entered.countDown(); long end=System.nanoTime()+Duration.ofSeconds(40).toNanos();
                // Deliberately ignore cancellation: the REAL lease/commit barrier must reject late output.
                while(proceed.getCount()>0&&System.nanoTime()<end) {try{proceed.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
            }
            if(purpose.equals("interpret")) return interpret(input);
            if(purpose.equals("prelearn")) {
                ArrayNode ids=Json.array();input.path("passages").forEach(p->ids.add(p.path("id").asText()));
                ObjectNode method=Json.object().put("title","核实事实再表达").put("when","资料足够时")
                    .put("steps","先核实事实，再说明结论和条件").put("limits","不把猜测当事实");method.set("sourceIds",ids.deepCopy());
                ObjectNode out=Json.object().put("summary","表达和倾听都应先核实事实与需求");out.set("methods",Json.array().add(method));out.set("sourceIds",ids);
                ObjectNode coverage=Json.object();coverage.set("processedSourceIds",ids.deepCopy());coverage.set("limitations",Json.array());out.set("coverage",coverage);return out;
            }
            if(!purpose.equals("generate")) throw new IllegalArgumentException("Unexpected test purpose: "+purpose);
            if(failGeneration) throw new IllegalStateException("Controlled generation failure");
            JsonNode source=input.path("sources").get(0);
            if(source==null) throw new IllegalArgumentException("generate requires real selected sources");
            String id=source.path("id").asText();
            ObjectNode body=Json.object();String kind=input.path("kind").asText();String role=input.path("agentRole").asText();
            switch(kind) {
                case "book_summary" -> {
                    body.put("title","事实、表达与倾听").put("summary","先核实事实和需求，再组织表达。修订依据："+input.path("changeReason").asText());
                    body.set("outline",Json.array().add("核实事实").add("确认需求"));body.set("limitations",Json.array().add("虚构短文验收，不是整书能力测评"));
                    body.set("specialists",Json.array().add(specialist("expression","表达专家",input.path("changeReason").asText().contains("只处理技术汇报")?"只处理技术汇报":"组织汇报"))
                        .add(specialist("listening","倾听专家","核对需求")));
                }
                case "agent" -> {
                    body.put("name",role+"-"+input.path("logicalKey").asText()).put("description","验收用候选分工")
                        .put("summary","依据所选资料工作").put("responsibility",role.equals("router")?"只路由，具体任务交专业专家":role.equals("specialist")?input.path("plan").path("responsibility").asText():"核实事实或需求")
                        .put("model","test-only-controlled-model").put("overlapAnalysis","表达与倾听职责不同，不替代对方确认");
                    body.set("tools",Json.array().add("read_source"));body.set("capabilities",Json.array().add("明确区分证据与猜测"));
                    body.set("boundaries",Json.array().add("不能发送消息"));body.set("chapters",Json.array().add("原创验收教材第一、二页"));
                }
                case "keyword_rule" -> {
                    body.set("rules",Json.array().add(rule("expression","汇报")).add(rule("listening","倾听")));
                    body.put("multiMatch","L3").put("noMatch","L2");
                }
                case "qa_example" -> {
                    body.set("examples",Json.array().add(example("expression","如何汇报延期？","说明证据及待确认依赖",id))
                        .add(example("listening","如何确认需求？","询问希望信息还是协助",id)));
                    body.put("metric","cosine").put("threshold",0.90).put("comparison",">").put("multiMatch","L3").put("noMatch","L3").put("embeddingProvider","aliyun-bailian");
                }
                case "team_manifest" -> {
                    body.put("summary","完整测试团队的管理员生产配置");body.set("flow",Json.array().add("L1多专家交L3，未命中才L2")
                        .add("L2余弦严格大于0.90，多专家或未命中交L3").add("L3只路由，专业专家执行"));body.set("limitations",Json.array().add("不包含用户侧执行"));
                }
                default -> throw new IllegalArgumentException("Unexpected artifact kind: "+kind);
            }
            ObjectNode out=Json.object();out.set("body",body);out.put("systemPrompt",kind.equals("agent")?"完整测试业务提示词：依据原文核实事实，职责="+role+"，不得猜测，不能发送消息；信息不足先追问。修订原因="+input.path("changeReason").asText():"");
            out.set("sources",Json.array().add(Json.object().put("chunkId",invalidSource?UUID.randomUUID().toString():id)
                .put("startOffset",0).put("endOffset",Math.min(8,source.path("text").asText().codePointCount(0,source.path("text").asText().length()))).put("purpose","方法依据")));
            ArrayNode questions=Json.array();if(clarifyFirstAgent&&kind.equals("agent")&&role.equals("specialist")){questions.add("用于内部汇报还是公开表达？");clarifyFirstAgent=false;}
            out.set("clarifications",questions);return out;
        }
        private static ObjectNode specialist(String key,String name,String responsibility){ObjectNode n=Json.object().put("key",key).put("name",name).put("responsibility",responsibility);n.set("methodTitles",Json.array().add("核实事实再表达"));return n;}
        private static ObjectNode rule(String key,String word){ObjectNode n=Json.object().put("specialistKey",key);n.set("keywords",Json.array().add(word));return n;}
        private static ObjectNode example(String key,String q,String a,String id){ObjectNode n=Json.object().put("specialistKey",key).put("question",q).put("answer",a);n.set("sourceIds",Json.array().add(id));return n;}
        private ObjectNode interpret(ObjectNode input) {
            String text=input.path("message").path("content").asText();JsonNode snapshot=input.path("snapshot");
            JsonNode context=input.path("message").path("reviewContext");
            String intent="ask",reason="";boolean conditional=text.contains("如果")||text.contains("后就"),quoted=text.contains("书中写")||text.contains("他说"),ambiguous=text.equals("可以")||text.contains("职责可以");
            if(text.startsWith("不通过")){intent="reject";if(text.contains("："))reason=text.substring(text.indexOf('：')+1);}
            if(text.startsWith("补充原因：")){intent="provide_reason";reason=text.substring(5);}
            if(text.startsWith("修改：")){intent="revise";reason=text.substring(3);}
            if(text.equals("当前完整内容准确，我确认通过")||maliciousApproval)intent="approve";
            ObjectNode out=Json.object().put("intent",intent).put("targetRevisionId",context.path("revisionId").asText())
                .put("scope",ambiguous?"agent.responsibility":context.path("scope").asText()).put("rewrittenText",text).put("reason",reason)
                .put("reply","测试专用模型公开答复").put("conditional",conditional).put("ambiguous",ambiguous).put("quoted",quoted);
            ArrayNode resolved=Json.array();if(intent.equals("provide_reason")||text.equals("仅用于内部汇报")) snapshot.path("clarifications").forEach(c->{if(c.path("status").asText().equals("open"))resolved.add(c.path("id").asText());});
            out.set("resolveClarificationIds",resolved);out.set("sourceIds",Json.array());return out;
        }
    }
}
