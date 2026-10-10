package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class ProductionProposalTest {
    @TempDir Path temporary;

    private static ObjectNode request(ProductionTestServer test) {
        ObjectNode pair=Json.object().put("documentId",test.document.path("documentId").asText())
            .put("documentVersionId",test.document.path("versionId").asText());
        return Json.object().set("documents",Json.array().add(pair));
    }
    static class ProposalModel extends ProductionTestServer.ControlledModel {
        boolean invalidCandidate;
        @Override public ObjectNode json(String purpose,ObjectNode input,BooleanSupplier cancelled)throws Exception {
            ObjectNode result=super.json(purpose,input,cancelled);
            if(purpose.equals("generate")&&input.path("kind").asText().equals("book_summary")) {
                String source=input.path("sources").get(0).path("id").asText();
                for(var candidate:result.with("body").withArray("specialists")) {
                    ObjectNode item=(ObjectNode)candidate;
                    item.set("typicalQuestions",Json.array().add("如何依据资料核实并回答这个问题？"));
                    item.set("sourceIds",Json.array().add(invalidCandidate?UUID.randomUUID().toString():source));
                }
            }
            return result;
        }
    }

    @Test void selectOnlyPostPersistsOneUnapprovedBuildAndMetadata() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            var response=test.request("POST","/proposals",request(test));
            assertEquals(202,response.status(),response.body().toString());
            String build=response.body().path("buildId").asText();assertFalse(build.isBlank());
            ObjectNode snapshot=test.snapshot(build);
            assertEquals("prelearning",snapshot.path("phase").asText());
            assertEquals(1,snapshot.path("proposal").path("documents").size());
            assertFalse(snapshot.path("proposal").path("sources").isEmpty());
            assertFalse(snapshot.path("proposal").path("sources").get(0).has("text"));
            assertEquals(0,snapshot.path("reviews").size());
            assertEquals(0,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_artifact WHERE build_id=? AND agent_role='specialist'",build)).path("n").asInt());
        }
    }

    @Test void repeatedAndConcurrentSelectionsReuseTheSameBuildAndJob() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            ObjectNode input=request(test);
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try {
                var futures=new java.util.ArrayList<Future<ProductionTestServer.Response>>();
                for(int i=0;i<4;i++)futures.add(pool.submit(()->test.request("POST","/proposals",input)));
                String build=null;
                for(var future:futures){var result=future.get();assertEquals(202,result.status(),result.body().toString());if(build==null)build=result.body().path("buildId").asText();else assertEquals(build,result.body().path("buildId").asText());}
                String accepted=build;
                assertEquals(1,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_job WHERE build_id=?",accepted)).path("n").asInt());
            } finally {pool.shutdownNow();}
        }
    }

    @Test void invalidOrDuplicateSelectionsCreateNothing() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            ObjectNode invalid=request(test);((ObjectNode)invalid.path("documents").get(0)).put("documentVersionId",UUID.randomUUID().toString());
            assertTrue(test.request("POST","/proposals",invalid).status()>=400);
            ObjectNode duplicate=request(test);((ArrayNode)duplicate.path("documents")).add(duplicate.path("documents").get(0).deepCopy());
            assertTrue(test.request("POST","/proposals",duplicate).status()>=400);
            assertEquals(0,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_build")).path("n").asInt());
        }
    }

    @Test void reorderedSelectionsReuseBuildButChangingVersionCreatesAnother() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            ObjectNode second=test.legacy.importDocument("第二份资料","",Json.array().add(Json.object().put("page",1).put("text","先确认目标和证据，再写结论。")),"fictional-2.pdf","%PDF-1.4\nsecond\n%%EOF".getBytes(StandardCharsets.UTF_8));
            ObjectNode newest=test.legacy.importDocument("原创验收教材",test.document.path("documentId").asText(),Json.array().add(Json.object().put("page",1).put("text","先定义问题，再列出已知条件。")),"fictional-v2.pdf","%PDF-1.4\nversion two\n%%EOF".getBytes(StandardCharsets.UTF_8));
            ObjectNode pair=(ObjectNode)request(test).path("documents").get(0);
            ObjectNode other=Json.object().put("documentId",second.path("documentId").asText()).put("documentVersionId",second.path("versionId").asText());
            ObjectNode forward=Json.object();forward.set("documents",Json.array().add(pair.deepCopy()).add(other.deepCopy()));
            ObjectNode reverse=Json.object();reverse.set("documents",Json.array().add(other.deepCopy()).add(pair.deepCopy()));
            String first=test.request("POST","/proposals",forward).body().path("buildId").asText();
            assertEquals(first,test.request("POST","/proposals",reverse).body().path("buildId").asText());
            ((ObjectNode)reverse.path("documents").get(1)).put("documentVersionId",newest.path("versionId").asText());
            String changed=test.request("POST","/proposals",reverse).body().path("buildId").asText();
            assertNotEquals(first,changed);
            assertEquals(2,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_build")).path("n").asInt());
        }
    }

    @Test void mismatchedDocumentAndVersionAreRejectedBeforeCreation() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            ObjectNode second=test.legacy.importDocument("另一资料","",Json.array().add(Json.object().put("page",1).put("text","不同资料中的方法。")),"other.pdf","%PDF-1.4\nother\n%%EOF".getBytes(StandardCharsets.UTF_8));
            ObjectNode input=request(test);((ObjectNode)input.path("documents").get(0)).put("documentVersionId",second.path("versionId").asText());
            assertEquals(422,test.request("POST","/proposals",input).status());
            assertEquals(0,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_build")).path("n").asInt());
        }
    }

    @Test void fullPrelearnGeneratesCandidateQuestionsAndSourcesWithoutApproval() throws Exception {
        ProposalModel model=new ProposalModel();
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=test.request("POST","/proposals",request(test)).body().path("buildId").asText();
            test.service.start();ObjectNode snapshot=awaitSummary(test,build);
            assertEquals("workbench",model.inputs.stream().filter(i->i.path("testPurpose").asText().equals("generate")).findFirst().orElseThrow().path("origin").asText());
            assertEquals(2,snapshot.path("summary").path("body").path("specialists").size());
            for(var candidate:snapshot.path("summary").path("body").path("specialists")) {
                assertFalse(candidate.path("typicalQuestions").isEmpty());assertFalse(candidate.path("sourceIds").isEmpty());
            }
            int calls=model.inputs.size();
            assertEquals(build,test.request("POST","/proposals",request(test)).body().path("buildId").asText());
            assertEquals(calls,model.inputs.size());
            assertEquals(0,snapshot.path("reviews").size());
            assertEquals(0,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_artifact WHERE build_id=? AND agent_role='specialist'",build)).path("n").asInt());
        }
    }

    @Test void invalidCandidateSourceFailsGenerationWithoutSealingSummary() throws Exception {
        ProposalModel model=new ProposalModel();model.invalidCandidate=true;
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=test.request("POST","/proposals",request(test)).body().path("buildId").asText();test.service.start();
            ObjectNode state=await(test,build,s->s.path("jobs").toString().contains("failed"));
            assertNotEquals("succeeded",state.path("summary").path("generationStatus").asText());
            assertEquals(0,state.path("reviews").size());
        }
    }

    @Test void cancelledDraftAllowsNewDeterministicAnalysis() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String first=test.request("POST","/proposals",request(test)).body().path("buildId").asText();
            ObjectNode state=test.snapshot(first);
            ObjectNode cancel=Json.object().put("clientRequestId",UUID.randomUUID().toString()).put("content","取消分析").put("expectedLockVersion",state.path("lockVersion").asText());
            cancel.set("action",Json.object().put("type","cancel"));
            assertEquals(202,test.request("POST","/builds/"+first+"/messages",cancel).status());
            String second=test.request("POST","/proposals",request(test)).body().path("buildId").asText();
            assertNotEquals(first,second);
            assertEquals(second,test.request("POST","/proposals",request(test)).body().path("buildId").asText());
            assertEquals("cancelled",test.snapshot(first).path("status").asText());
        }
    }

    @Test void pausedDraftIsReusedWithoutResumingItsJob() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=test.request("POST","/proposals",request(test)).body().path("buildId").asText();
            ObjectNode state=test.snapshot(build);
            ObjectNode pause=Json.object().put("clientRequestId",UUID.randomUUID().toString()).put("content","暂停分析").put("expectedLockVersion",state.path("lockVersion").asText());
            pause.set("action",Json.object().put("type","pause"));
            assertEquals(202,test.request("POST","/builds/"+build+"/messages",pause).status());
            assertEquals(build,test.request("POST","/proposals",request(test)).body().path("buildId").asText());
            assertEquals("paused",test.snapshot(build).path("status").asText());
            assertEquals(1,test.db.read(c->ProductionDatabase.one(c,"SELECT count(*) n FROM ep_build")).path("n").asInt());
        }
    }

    private static ObjectNode awaitSummary(ProductionTestServer test,String build)throws Exception {
        return await(test,build,s->s.path("summary").path("generationStatus").asText().equals("succeeded"));
    }
    private static ObjectNode await(ProductionTestServer test,String build,java.util.function.Predicate<ObjectNode> ready)throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(25).toNanos();ObjectNode state;
        do{state=test.snapshot(build);if(ready.test(state))return state;Thread.sleep(50);}while(System.nanoTime()<deadline);
        throw new AssertionError("Timed out waiting for summary: "+state.path("jobs"));
    }
}
