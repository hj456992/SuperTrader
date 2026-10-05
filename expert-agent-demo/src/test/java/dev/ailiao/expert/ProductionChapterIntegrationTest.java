package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL",matches=".+")
class ProductionChapterIntegrationTest {
    @TempDir Path root;
    @Test void legacySourceWithoutChapterMetadataProducesReviewableAgentPageLabels()throws Exception{
        try(var test=new ProductionTestServer(root)){
            var service=new ProductionService(test.db,(purpose,input,cancelled)->{
                ObjectNode result=test.model.json(purpose,input,cancelled);
                if(purpose.equals("generate")&&input.path("kind").asText().equals("agent"))((ObjectNode)result.path("body")).set("chapters",Json.array());
                return result;
            },test.legacy,test.redis,root.resolve("shared-fixture"));test.services.add(service);
            String build=Json.id();service.create(build,test.createRequest());service.start();ObjectNode summary=awaitReview(service,build,"book_summary");
            ObjectNode context=null;for(JsonNode message:summary.path("messages"))if(message.path("presentation").path("revisionId").equals(summary.path("currentArtifact").path("currentRevision").path("id")))context=((ObjectNode)message.path("presentation")).deepCopy().put("presentedMessageId",message.path("id").asText());
            assertNotNull(context);ObjectNode request=Json.object().put("clientRequestId",Json.id()).put("content","明确批准当前完整概要").put("expectedLockVersion",summary.path("lockVersion").asText());request.set("reviewContext",context);request.set("action",Json.object().put("type","approve").put("scope","summary.full"));service.message(build,request);
            ObjectNode state=awaitReview(service,build,"agent");JsonNode revision=state.path("currentArtifact").path("currentRevision");
            assertEquals("pending",revision.path("reviewStatus").asText());assertFalse(revision.path("body").path("chapters").isEmpty());
            JsonNode source=revision.path("sources").get(0);ObjectNode actual=service.source(build,source.path("chunkId").asText(),null,null);
            assertEquals("",actual.path("chapterPath").asText());assertEquals("文档版本 "+actual.path("documentVersionId").asText()+"：未识别章节，第"+actual.path("pageNo").asInt()+"页",revision.path("body").path("chapters").get(0).asText());
            assertEquals(0,source.path("startOffset").asInt());assertEquals(8,source.path("endOffset").asInt());assertEquals("方法依据",source.path("purpose").asText());
        }
    }
    private static ObjectNode awaitReview(ProductionService service,String build,String kind)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(System.nanoTime()<end){ObjectNode state=service.snapshot(build);for(JsonNode job:state.path("jobs"))assertNotEquals("failed",job.path("status").asText(),job.toString());if(state.path("currentArtifact").path("kind").asText().equals(kind)&&state.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"))return state;Thread.sleep(40);}throw new AssertionError("未生成待审"+kind);
    }
}
