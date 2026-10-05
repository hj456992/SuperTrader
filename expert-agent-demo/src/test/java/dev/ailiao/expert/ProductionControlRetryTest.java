package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL",matches=".+")
class ProductionControlRetryTest {
    @TempDir Path root;
    @Test void repeatedNewClientControlsReuseQueuedAndRunningPrelearning()throws Exception{
        var model=new ProductionTestServer.ControlledModel();model.block("prelearn","");
        try(var test=new ProductionTestServer(root,model)){
            String build=Json.id();test.service.create(build,test.createRequest());
            controls(test,build);assertEquals(1,jobs(test,build,"prelearn"));assertEquals(0,calls(model,"prelearn"));
            test.service.start();assertTrue(model.entered.await(15,TimeUnit.SECONDS));controls(test,build);
            assertEquals(1,jobs(test,build,"prelearn"));assertEquals(1,calls(model,"prelearn"));model.release();
            await(test,build,s->s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"));assertEquals(1,calls(model,"prelearn"));
        }
    }
    @Test void repeatedNewClientControlsDoNotDuplicateBlockedGenerationOrReseal()throws Exception{
        var model=new ProductionTestServer.ControlledModel();model.block("generate","book_summary");
        try(var test=new ProductionTestServer(root,model)){
            String build=Json.id();test.service.create(build,test.createRequest());test.service.start();assertTrue(model.entered.await(15,TimeUnit.SECONDS));
            controls(test,build);assertEquals(1,jobs(test,build,"generate_summary"));assertEquals(1,calls(model,"generate"));model.release();
            await(test,build,s->s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"));
            controls(test,build);assertEquals(1,jobs(test,build,"generate_summary"));assertEquals(1,calls(model,"generate"));
            assertEquals(0,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_job WHERE build_id=? AND status='failed'",build)).path("n").asInt());
        }
    }
    @Test void failedWorkNeedsRetryAndPausedWorkCanResumeWithoutDuplicateCalls()throws Exception{
        var model=new ProductionTestServer.ControlledModel();model.failGeneration=true;
        try(var test=new ProductionTestServer(root,model)){
            String build=Json.id();test.service.create(build,test.createRequest());test.service.start();await(test,build,s->s.path("jobs").findValuesAsText("status").contains("failed"));
            ObjectNode response=control(test,build,"resume");assertEquals(409,response.path("httpStatus").asInt());assertEquals(1,jobs(test,build,"generate_summary"));
            model.failGeneration=false;model.block("generate","book_summary");control(test,build,"retry");assertTrue(model.entered.await(15,TimeUnit.SECONDS));controls(test,build);
            assertEquals(2,jobs(test,build,"generate_summary"));assertEquals(2,calls(model,"generate"));
            control(test,build,"pause");assertEquals("paused",test.service.snapshot(build).path("status").asText());model.release();control(test,build,"resume");
            await(test,build,s->s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded"));
            assertEquals(3,jobs(test,build,"generate_summary"));assertEquals(3,calls(model,"generate"));controls(test,build);assertEquals(3,jobs(test,build,"generate_summary"));
        }
    }
    private static void controls(ProductionTestServer test,String build)throws Exception{for(String type:java.util.List.of("resume","retry","resume","retry"))control(test,build,type);}
    private static ObjectNode control(ProductionTestServer test,String build,String type)throws Exception{
        ObjectNode request=Json.object().put("clientRequestId",Json.id()).put("content",type.equals("resume")?"继续":"重试").put("expectedLockVersion",test.service.snapshot(build).path("lockVersion").asText());request.set("action",Json.object().put("type",type));return test.service.message(build,request);
    }
    private static int jobs(ProductionTestServer test,String build,String kind)throws Exception{return test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_job WHERE build_id=? AND kind=?",build,kind)).path("n").asInt();}
    private static long calls(ProductionTestServer.ControlledModel model,String purpose){return model.inputs.stream().filter(n->purpose.equals(n.path("testPurpose").asText())).count();}
    private static ObjectNode await(ProductionTestServer test,String build,Predicate<ObjectNode> ready)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(System.nanoTime()<end){ObjectNode state=test.service.snapshot(build);if(ready.test(state))return state;Thread.sleep(40);}throw new AssertionError("未达到所需任务状态");
    }
}
