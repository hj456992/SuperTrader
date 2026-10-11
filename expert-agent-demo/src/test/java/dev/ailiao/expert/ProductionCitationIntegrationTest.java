package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL",matches=".+")
class ProductionCitationIntegrationTest {
    @TempDir Path root;
    @Test void invalidSelectionStaysUnsealedAndRetryPersistsExactOriginalWithoutApproval() throws Exception {
        class Model extends ProductionTestServer.ControlledModel {
            volatile boolean invalid=true;
            @Override public ObjectNode json(String purpose,ObjectNode input,BooleanSupplier cancelled)throws Exception {
                ObjectNode out=super.json(purpose,input,cancelled);
                if(purpose.equals("generate")) {
                    ObjectNode source=(ObjectNode)out.path("sources").get(0);source.remove("startOffset");source.remove("endOffset");
                    source.set("spanIds",Json.array().add(invalid?"s999":"s1"));
                }
                return out;
            }
        }
        var model=new Model();try(var test=new ProductionTestServer(root,model)) {
            String build=Json.id();test.service.create(build,test.createRequest());test.service.start();
            ObjectNode failed=await(test,build,"failed");JsonNode target=failed.path("currentArtifact").path("currentRevision");String id=target.path("id").asText();
            assertTrue(target.path("contentSha256").asText().isBlank());assertTrue(target.path("sources").isEmpty());assertTrue(failed.path("reviews").isEmpty());
            var learned=failed.path("artifacts").findValuesAsText("contentSha256");
            model.invalid=false;ObjectNode retry=Json.object().put("clientRequestId",Json.id()).put("content","重试失败任务").put("expectedLockVersion",failed.path("lockVersion").asText());retry.set("action",Json.object().put("type","retry"));test.service.message(build,retry);
            ObjectNode ready=await(test,build,"succeeded");JsonNode revision=ready.path("currentArtifact").path("currentRevision");
            assertEquals(id,revision.path("id").asText());assertEquals("pending",revision.path("reviewStatus").asText());assertTrue(ready.path("reviews").isEmpty());
            JsonNode source=revision.path("sources").get(0);ObjectNode actual=test.service.source(build,source.path("chunkId").asText(),source.path("startOffset").asInt(),source.path("endOffset").asInt());
            assertEquals("😀汇报先核实可验证事实，再说明条件和结论。例：工程延误两天，应说明证据与待确认依赖。",actual.path("text").asText());
            assertEquals("方法依据",source.path("purpose").asText());assertFalse(source.has("spanIds"));assertTrue(ready.path("artifacts").findValuesAsText("contentSha256").containsAll(learned.stream().filter(s->!s.isBlank()).toList()));
        }
    }
    private static ObjectNode await(ProductionTestServer test,String build,String status)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<end) {
            ObjectNode s=test.snapshot(build);
            if(s.path("currentArtifact").path("kind").asText().equals("book_summary")&&s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals(status)&&s.path("jobs").findValuesAsText("status").stream().noneMatch(v->v.equals("running")||v.equals("queued")))return s;
            Thread.sleep(40);
        }
        throw new AssertionError("Citation generation did not reach "+status);
    }
}
