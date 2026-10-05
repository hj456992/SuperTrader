package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL",matches=".+")
class ProductionInterpretationTest {
    @TempDir Path root;
    @Test void missingNullOrBlankRewritePreservesExactOriginalWithoutAdvancing()throws Exception{
        try(var test=new ProductionTestServer(root)){
            AtomicReference<ObjectNode> output=new AtomicReference<>();
            var service=new ProductionService(test.db,(purpose,input,cancelled)->purpose.equals("interpret")?output.get().deepCopy():test.model.json(purpose,input,cancelled),test.legacy,test.redis,root.resolve("shared-fixture"));test.services.add(service);
            String build=Json.id();service.create(build,test.createRequest());service.start();ObjectNode before=awaitReview(service,build);
            String original="  请解释这份概要的适用范围，不要推进审核。😀\n";
            for(int shape=0;shape<5;shape++){
                ObjectNode intent=answer();if(shape==1)intent.putNull("rewrittenText");if(shape==2)intent.put("rewrittenText"," \n\t");if(shape==3)intent.put("rewrittenText",original);if(shape==4)intent.put("rewrittenText","请说明适用范围，保持待审。");output.set(intent);
                ObjectNode message=submit(service,build,original);
                assertEquals("succeeded",message.path("processingStatus").asText(),message.toString());
                assertEquals(shape==4?"请说明适用范围，保持待审。":original,message.path("interpretation").path("rewrittenText").asText());
                assertEquals(shape==4,message.path("interpretation").path("rewritten").asBoolean());
                assertEquals(original,message.path("content").asText());
                ObjectNode after=service.snapshot(build);assertEquals(before.path("currentArtifactId"),after.path("currentArtifactId"));assertEquals(before.path("lockVersion"),after.path("lockVersion"));assertEquals("pending",after.path("currentArtifact").path("currentRevision").path("reviewStatus").asText());
            }
            for(JsonNode invalid:new JsonNode[]{IntNode.valueOf(3),BooleanNode.FALSE,Json.object(),Json.array()}){
                ObjectNode intent=answer();intent.set("rewrittenText",invalid);output.set(intent);
                ObjectNode message=submit(service,build,original);assertEquals("failed",message.path("processingStatus").asText());
            }
        }
    }
    @Test void unsafeIntentCannotMutateControlReviewFocusOrClarifications()throws Exception{
        try(var test=new ProductionTestServer(root)){
            AtomicReference<ObjectNode> output=new AtomicReference<>();
            var service=new ProductionService(test.db,(purpose,input,cancelled)->purpose.equals("interpret")?output.get().deepCopy():test.model.json(purpose,input,cancelled),test.legacy,test.redis,root.resolve("shared-fixture"));test.services.add(service);
            String build=Json.id();service.create(build,test.createRequest());service.start();ObjectNode before=awaitReview(service,build);
            for(String type:java.util.List.of("cancel","pause","resume","retry","approve","reject","revise","provide_reason","focus","ask","read_source")){
                for(String flag:java.util.List.of("conditional","ambiguous","quoted")){
                    ObjectNode intent=answer().put("intent",type).put(flag,true).put("reason","只用于内部汇报").put("targetRevisionId",before.path("currentArtifact").path("currentRevision").path("id").asText());
                    intent.set("resolveClarificationIds",Json.array().add(Json.id()));output.set(intent);
                    ObjectNode message=submit(service,build,"如果材料有误，再考虑处理；这是引文或尚不确定的多意图。");
                    assertEquals("succeeded",message.path("processingStatus").asText(),type+"/"+flag+":"+message);
                    assertEquals("clarify",message.path("result").path("type").asText(),type+"/"+flag);
                    assertUnchanged(before,service.snapshot(build));
                }
            }
            for(String flag:java.util.List.of("conditional","ambiguous","quoted"))for(JsonNode invalid:new JsonNode[]{MissingNode.getInstance(),NullNode.getInstance(),TextNode.valueOf("false"),IntNode.valueOf(0)}){
                ObjectNode intent=answer().put("intent","cancel");if(invalid.isMissingNode())intent.remove(flag);else intent.set(flag,invalid);output.set(intent);
                ObjectNode message=submit(service,build,"操作含义待确认");assertEquals("clarify",message.path("result").path("type").asText());assertUnchanged(before,service.snapshot(build));
            }
        }
    }
    private static void assertUnchanged(ObjectNode before,ObjectNode after){
        for(String field:java.util.List.of("status","phase","lockVersion","currentArtifactId","focusRevisionId","artifacts","clarifications","reviews"))assertEquals(before.path(field),after.path(field),field+"不应被不安全意图修改");
    }
    private static ObjectNode answer(){ObjectNode out=Json.object().put("intent","ask").put("targetRevisionId","").put("scope","").put("reason","").put("reply","可用于依据事实组织表达，资料不足时先核实。").put("conditional",false).put("ambiguous",false).put("quoted",false);out.set("sourceIds",Json.array());out.set("resolveClarificationIds",Json.array());return out;}
    private static ObjectNode submit(ProductionService service,String build,String text)throws Exception{
        ObjectNode request=Json.object().put("clientRequestId",Json.id()).put("content",text).put("expectedLockVersion",service.snapshot(build).path("lockVersion").asText());
        String id=service.message(build,request).path("messageId").asText();long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();
        while(System.nanoTime()<end){for(JsonNode m:service.snapshot(build).path("messages"))if(m.path("id").asText().equals(id)&&java.util.Set.of("succeeded","failed").contains(m.path("processingStatus").asText()))return (ObjectNode)m;Thread.sleep(30);}throw new AssertionError("消息未完成");
    }
    private static ObjectNode awaitReview(ProductionService service,String build)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<end){ObjectNode state=service.snapshot(build);if(state.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"))return state;Thread.sleep(30);}throw new AssertionError("未生成待审稿");
    }
}
