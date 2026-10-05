package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL",matches=".+")
class ProductionEmptyPrelearnIntegrationTest {
    @TempDir Path root;
    @Test void emptyTailRetryKeepsCompletedBatchAndAllOriginalReferences()throws Exception{
        try(var test=new ProductionTestServer(root)){
            var doc=test.legacy.importDocument("原创方法及书末资料","",Json.array().add(Json.object().put("page",1).put("text","核实事实再表达。".repeat(2000))).add(Json.object().put("page",2).put("text","参考书目与致谢。".repeat(200))),"fixture.pdf",new byte[]{1,2,3});
            AtomicBoolean explainEmpty=new AtomicBoolean();
            var service=new ProductionService(test.db,(purpose,input,cancelled)->{
                ObjectNode result=test.model.json(purpose,input,cancelled);
                if(purpose.equals("prelearn")&&input.path("batchNo").asInt()>0){result.set("methods",Json.array());if(explainEmpty.get())((ObjectNode)result.path("coverage")).put("noMethodReason","本批为参考书目与致谢，没有可提取方法。");}
                return result;
            },test.legacy,test.redis,root.resolve("shared-fixture"));test.services.add(service);
            ObjectNode request=test.createRequest();request.set("documents",Json.array().add(Json.object().put("documentId",doc.path("documentId").asText()).put("documentVersionId",doc.path("versionId").asText())));
            String build=Json.id();service.create(build,request);service.start();await(service,build,s->s.path("jobs").findValuesAsText("status").contains("failed"));
            ObjectNode sealed=test.db.read(c->ProductionDatabase.one(c,"SELECT r.id,r.content_sha256 FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.logical_key='learning:0'",build));
            assertEquals(1,test.model.inputs.stream().filter(n->n.path("testPurpose").asText().equals("prelearn")&&n.path("batchNo").asInt()==0).count());
            explainEmpty.set(true);ObjectNode retry=Json.object().put("clientRequestId",Json.id()).put("content","明确重试失败批次").put("expectedLockVersion",service.snapshot(build).path("lockVersion").asText());retry.set("action",Json.object().put("type","retry"));service.message(build,retry);
            ObjectNode ready=await(service,build,s->s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"));assertEquals("pending",ready.path("currentArtifact").path("currentRevision").path("reviewStatus").asText());
            assertEquals(sealed,test.db.read(c->ProductionDatabase.one(c,"SELECT r.id,r.content_sha256 FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.logical_key='learning:0'",build)));
            assertEquals(1,test.model.inputs.stream().filter(n->n.path("testPurpose").asText().equals("prelearn")&&n.path("batchNo").asInt()==0).count());
            ObjectNode tail=test.db.read(c->ProductionDatabase.one(c,"SELECT r.body,jsonb_array_length(r.generator_config->'passages') AS passages,(SELECT count(*) FROM ep_revision_source s WHERE s.revision_id=r.id) AS refs FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.logical_key='learning:1'",build));
            assertTrue(tail.path("body").path("methods").isEmpty());assertEquals(tail.path("passages").asInt(),tail.path("refs").asInt());assertTrue(tail.path("refs").asInt()>0);
            ObjectNode generation=test.model.inputs.stream().filter(n->n.path("testPurpose").asText().equals("generate")).findFirst().orElseThrow();assertEquals(2,generation.path("learningUnits").size());assertEquals("本批为参考书目与致谢，没有可提取方法。",generation.path("learningUnits").get(1).path("noMethodReason").asText());
        }
    }
    private static ObjectNode await(ProductionService service,String build,Predicate<ObjectNode> ready)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(25).toNanos();while(System.nanoTime()<end){ObjectNode state=service.snapshot(build);if(ready.test(state))return state;Thread.sleep(40);}throw new AssertionError("未达到预学习状态");}
}
