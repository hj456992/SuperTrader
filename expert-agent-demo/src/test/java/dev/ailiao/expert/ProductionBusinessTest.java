package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL", matches=".+")
class ProductionBusinessTest {
    @TempDir Path root;
    ProductionDatabase db;
    ProductionService service;
    String schema;
    @BeforeEach void setup() throws Exception {
        schema="ep_business_"+UUID.randomUUID().toString().replace("-", "");
        db=new ProductionDatabase(System.getenv("EXPERT_DB_URL"),System.getenv("EXPERT_DB_USER"),System.getenv("EXPERT_DB_PASSWORD"),schema);
        db.migrate();
    }
    @AfterEach void cleanup() throws Exception {
        if(service!=null)service.close();
        if(db!=null)db.transaction(c->{ProductionDatabase.execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});
    }
    @Test void creationPersistsDraftsBeforeWorkersAndIdempotencyDoesNotCreateTwice() throws Exception {
        LabStore legacy=new LabStore(root.resolve("legacy"));
        var imported=legacy.importDocument("测试资料","",Json.array().add(Json.object().put("page",1).put("text","先说明结论，再列出支持结论的理由。")),"test.pdf",new byte[]{1,2,3});
        service=new ProductionService(db,(purpose,input,cancelled)->{throw new AssertionError("创建事务不应调用模型");},legacy,ProductionRedis.fromEnvironment(),root.resolve("shared"));
        ObjectNode request=Json.object().put("teamId",Json.id()).put("teamName","测试团队").put("name","表达专家").put("responsibility","组织观点");
        request.set("documents",Json.array().add(Json.object().put("documentId",imported.path("documentId").asText()).put("documentVersionId",imported.path("versionId").asText())));
        String id=Json.id();assertEquals(202,service.create(id,request).path("httpStatus").asInt());
        var snapshot=service.snapshot(id);assertEquals("prelearning",snapshot.path("phase").asText());
        assertFalse(snapshot.path("artifacts").isEmpty());assertFalse(snapshot.path("jobs").isEmpty());
        service.create(id,request);
        assertEquals(1,db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_build")).path("n").asInt());
        assertThrows(ProductionException.class,()->service.create(id,request.deepCopy().put("name","冲突名称")));
    }
    @Test void modelOutputCannotInventSourcesOrOmitFullPrompt() throws Exception {
        ObjectNode output=Json.object();output.set("body",Json.object().put("name","fake"));output.set("sources",Json.array());
        assertThrows(IllegalArgumentException.class,()->ProductionRules.validateGenerated("agent",output,Json.array(),Json.array()));
        ObjectNode intent=Json.object().put("intent","approve").put("scope","artifact.full").put("conditional",true);
        assertFalse(ProductionRules.unconditionalApproval(intent));
        assertFalse(ProductionRules.unconditionalApproval(intent.put("conditional",false).put("quoted",true)));
    }
    @Test void allStagesRequireExplicitCurrentFullReviewAndFinishWithVersionedManifest() throws Exception {
        try(var test=new ProductionTestServer(root.resolve("flow"))){
            String build=Json.id();test.service.create(build,test.createRequest());test.service.start();
            java.util.List<String> seen=new java.util.ArrayList<>();
            for(int i=0;i<12;i++){
                ObjectNode snapshot=awaitReview(test,build);
                if(snapshot.path("status").asText().equals("completed"))break;
                var artifact=snapshot.path("currentArtifact");String identity=artifact.path("id").asText();
                seen.add(snapshot.path("phase").asText());
                assertEquals("pending",artifact.path("currentRevision").path("reviewStatus").asText());
                Thread.sleep(100);assertEquals(identity,test.service.snapshot(build).path("currentArtifactId").asText(),"生成完毕不可自动批准/推进");
                ObjectNode context=null;for(var message:snapshot.path("messages"))if(message.path("presentation").path("revisionId").asText().equals(artifact.path("currentRevision").path("id").asText()))context=((ObjectNode)message.path("presentation")).deepCopy().put("presentedMessageId",message.path("id").asText());
                assertNotNull(context);ObjectNode request=Json.object().put("clientRequestId",Json.id()).put("content","管理员测试批准当前完整版本").put("expectedLockVersion",snapshot.path("lockVersion").asText());request.set("reviewContext",context);request.set("action",Json.object().put("type","approve").put("scope",context.path("scope").asText()));
                var queued=test.service.message(build,request);assertEquals(202,queued.path("httpStatus").asInt());String messageId=queued.path("messageId").asText();long end=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
                boolean processed=false;while(System.nanoTime()<end){var current=test.service.snapshot(build);for(var message:current.path("messages"))if(message.path("id").asText().equals(messageId)&&!message.path("result").isNull()){assertEquals(200,message.path("result").path("httpStatus").asInt(),message.toString());processed=true;}if(processed)break;Thread.sleep(50);}assertTrue(processed,"审核消息应终结");
            }
            ObjectNode finalState=test.service.snapshot(build);assertEquals("completed",finalState.path("status").asText(),finalState.toString());
            assertTrue(seen.containsAll(java.util.List.of("summary","specialists","fallback","router","production_config","final_review")));
            var manifest=finalState.path("currentArtifact").path("currentRevision");assertTrue(manifest.path("body").path("members").size()>=7);assertEquals("completed",manifest.path("reviewStatus").asText());
        }
    }
    @Test void cancellationRemainsAvailableWhileSemanticMessageWaitsForQuota() throws Exception {
        try(var test=new ProductionTestServer(root.resolve("quota-control"))){
            String build=Json.id();test.service.create(build,test.createRequest());test.service.start();awaitReview(test,build);test.service.close();
            int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
            ProductionService limited=new ProductionService(test.db,test.model,test.legacy,new ProductionRedis("redis://127.0.0.1:"+port,""),test.root.resolve("shared-fixture"));test.services.add(limited);limited.start();
            ObjectNode before=limited.snapshot(build);ObjectNode ask=Json.object().put("clientRequestId",Json.id()).put("content","请解释当前概要").put("expectedLockVersion",before.path("lockVersion").asText());assertEquals(202,limited.message(build,ask).path("httpStatus").asInt());
            long end=System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos();boolean waiting=false;
            while(System.nanoTime()<end){ObjectNode now=limited.snapshot(build);for(var job:now.path("jobs"))if(job.path("kind").asText().equals("interpret_message")&&job.path("phase").asText().equals("quota_wait"))waiting=true;if(waiting)break;Thread.sleep(50);}assertTrue(waiting);
            ObjectNode state=limited.snapshot(build);ObjectNode cancel=Json.object().put("clientRequestId",Json.id()).put("content","取消构建").put("expectedLockVersion",state.path("lockVersion").asText());cancel.set("action",Json.object().put("type","cancel"));
            ObjectNode response=limited.message(build,cancel);assertEquals(202,response.path("httpStatus").asInt(),response.toString());
            assertEquals("cancelled",limited.snapshot(build).path("status").asText(),"无需模型的取消不得被额度等待阻断");
            assertTrue(Long.parseLong(limited.snapshot(build).path("lastEventSeq").asText())>Long.parseLong(before.path("lastEventSeq").asText()));
        }
    }
    @Test void legacyDocumentWithoutParserUsesExplicitUnknownMetadata() throws Exception {
        try(var test=new ProductionTestServer(root.resolve("legacy-parser"))){
            Path state=test.root.resolve("legacy-fixture/state.json");ObjectNode old=Json.parse(java.nio.file.Files.readString(state));
            ((ObjectNode)old.path("documents").get(0).path("versions").get(0)).remove("parser");java.nio.file.Files.writeString(state,old.toString());
            var historical=new LabStore(test.root.resolve("legacy-fixture"));var service=new ProductionService(test.db,test.model,historical,new ProductionRedis(null,null),test.root.resolve("shared-fixture"));test.services.add(service);
            String id=Json.id();assertEquals(202,service.create(id,test.createRequest()).path("httpStatus").asInt());
            ObjectNode version=test.db.read(c->ProductionDatabase.one(c,"SELECT parser_config FROM ep_document_version WHERE id=?",test.document.path("versionId").asText()));
            assertEquals("unknown",version.path("parser_config").path("provider").asText());assertEquals("legacy",version.path("parser_config").path("origin").asText());
        }
    }
    private static ObjectNode awaitReview(ProductionTestServer test,String build)throws Exception{
        long end=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();ObjectNode latest=null;
        while(System.nanoTime()<end){latest=test.service.snapshot(build);if(latest.path("status").asText().equals("completed"))return latest;
            for(var job:latest.path("jobs"))if(job.path("status").asText().equals("failed"))fail("任务失败："+job);
            if(latest.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded")&&latest.path("currentArtifact").path("currentRevision").path("reviewStatus").asText().equals("pending"))return latest;Thread.sleep(50);
        }throw new AssertionError("未出现待审成果："+latest);
    }
}
