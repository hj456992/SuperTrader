package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + PostgreSQL independent checks. Semantic outputs are explicitly TEST ONLY. */
@Timeout(90)
class ProductionAcceptanceTest {
    @TempDir Path temporary;

    @Test void creationPersistsPendingWorkBeforeAnyModelRuns() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=UUID.randomUUID().toString();
            var created=test.request("PUT","/builds/"+build,test.createRequest());
            assertEquals(202,created.status(),created.body().toString());
            ObjectNode stored=test.db.read(c->ProductionDatabase.one(c,"SELECT phase,status FROM ep_build WHERE id=?",build));
            assertNotNull(stored,"build persists before worker/model start");
            assertEquals("prelearning",stored.path("phase").asText());assertEquals("active",stored.path("status").asText());
            assertTrue(count(test,"ep_job",build,"status='queued'")>0,"durable queued job required");
            assertTrue(count(test,"ep_artifact_revision",build,"review_status='pending'")>0,"draft persists from creation");
            assertEquals(0,count(test,"ep_review",build,"true"),"generation is never approval");
        }
    }

    @Test void completeTwelveStepsRequireEveryDisplayedArtifactAndFinalManifestApproval() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();
            List<String> phases=new ArrayList<>();Set<String> roles=new HashSet<>();Set<String> kinds=new HashSet<>();
            Set<String> approved=new HashSet<>();ObjectNode state=ready(test,build,"");
            for(int step=0;step<12&&!state.path("status").asText().equals("completed");step++) {
                assertEquals("active",state.path("status").asText());
                ObjectNode a=current(state),r=revision(state);String id=r.path("id").asText();
                assertTrue(approved.add(id),"same revision cannot be approved as another step");
                assertEquals("pending",r.path("reviewStatus").asText());
                assertFalse(r.path("sources").isEmpty(),"each generated artifact must expose source evidence");
                assertTrue(r.path("contentSha256").asText().matches("[a-f0-9]{64}"));
                phases.add(state.path("phase").asText());kinds.add(a.path("kind").asText());
                if(a.path("kind").asText().equals("agent")) {
                    roles.add(a.path("agentRole").asText());JsonNode body=r.path("body");
                    for(String f:List.of("name","description","summary","responsibility","model","tools","capabilities","boundaries","chapters","overlapAnalysis"))
                        assertTrue(body.hasNonNull(f),"reviewable agent field missing: "+f);
                    assertTrue(r.path("systemPrompt").asText().contains("完整测试业务提示词"),"API must expose full runtime prompt, not a summary");
                }
                if(a.path("kind").asText().equals("keyword_rule")) {
                    assertEquals("L3",r.path("body").path("multiMatch").asText());assertEquals("L2",r.path("body").path("noMatch").asText());
                    assertEquals(2,r.path("body").path("rules").size());
                }
                if(a.path("kind").asText().equals("qa_example")) {
                    JsonNode body=r.path("body");assertEquals("cosine",body.path("metric").asText());assertEquals(0.90,body.path("threshold").asDouble());
                    assertEquals(">",body.path("comparison").asText());assertEquals("L3",body.path("multiMatch").asText());assertEquals("L3",body.path("noMatch").asText());
                    assertEquals(2,body.path("examples").size());
                }
                approve(test,build,state);state=await(test,build,s->s.path("status").asText().equals("completed")||isReady(s)&&!revision(s).path("id").asText().equals(id));
            }
            assertEquals("completed",state.path("status").asText(),"all twelve production requirements must reach actual completion");
            assertEquals(List.of("summary","specialists","specialists","fallback","router","production_config","production_config","final_review"),phases);
            assertEquals(Set.of("specialist","fallback","router"),roles);
            assertTrue(kinds.containsAll(Set.of("book_summary","agent","keyword_rule","qa_example","team_manifest")));
            assertEquals(approved.size(),count(test,"ep_review",build,"decision='approve'"));
            assertEquals(0,count(test,"ep_clarification",build,"status='open'"));
            ObjectNode team=test.db.read(c->ProductionDatabase.one(c,"SELECT completed_build_id FROM ep_team WHERE id=(SELECT team_id FROM ep_build WHERE id=?)",build));
            assertEquals(build,team.path("completed_build_id").asText(),"final approved snapshot links to team");
            assertEquals(0,test.legacy.state().path("experts").size(),"legacy trial/activation is not a prerequisite");
        }
    }

    @Test void rejectionWithoutReasonPersistsQuestionAndNewReasonCreatesUnapprovedVersion() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();ObjectNode initial=ready(test,build,"");String original=revision(initial).path("id").asText();
            ObjectNode rejected=request(initial,"不通过","reject");post(test,build,rejected,202);
            ObjectNode needs=await(test,build,s->revision(s).path("reviewStatus").asText().equals("needs_reason"));
            assertTrue(needs.path("clarifications").findValuesAsText("status").contains("open"));
            ObjectNode deniedApprove=request(needs,"当前完整内容准确，我确认通过","approve");postAcceptedOrConflict(test,build,deniedApprove);settled(test,build);
            assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
            ObjectNode reason=request(test.snapshot(build),"补充原因：必须明确不可把猜测当事实",null);post(test,build,reason,202);
            ObjectNode revised=ready(test,build,original);assertEquals("pending",revision(revised).path("reviewStatus").asText());
            assertTrue(revision(revised).path("body").path("summary").asText().contains("必须明确不可把猜测当事实"));
            assertEquals("summary",revised.path("phase").asText());
            assertEquals(1,count(test,"ep_review",build,"decision='reject' AND reason IS NULL"),"original missing-reason rejection is immutable");
            assertTrue(count(test,"ep_review",build,"decision='reject' AND reason IS NOT NULL")>0);
        }
    }

    @Test void conditionsQuotesPartialApprovalAndConversationDoNotApproveFullArtifact() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.maliciousApproval=true;
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();ObjectNode initial=ready(test,build,"");String revision=revision(initial).path("id").asText();
            for(String message:List.of("如果修改后就可以","他说确认通过","职责可以","可以")) {
                post(test,build,request(test.snapshot(build),message,null),202);settled(test,build);
                assertEquals("summary",test.snapshot(build).path("phase").asText(),message);
                assertEquals(0,count(test,"ep_review",build,"decision='approve'"),message);
            }
            model.maliciousApproval=false;post(test,build,request(test.snapshot(build),"为什么先核实事实？",null),202);settled(test,build);
            assertEquals(revision,revision(test.snapshot(build)).path("id").asText());
            assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
        }
    }

    @Test void idempotencyAndOldPresentationCannotDuplicateOrApproveNewRevision() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=UUID.randomUUID().toString();ObjectNode create=test.createRequest();postCreate(test,build,create,202);
            var replay=test.request("PUT","/builds/"+build,create);assertTrue(Set.of(200,202).contains(replay.status()));
            ObjectNode changed=create.deepCopy().put("name","another");postCreate(test,build,changed,409);
            test.service.start();ObjectNode initial=ready(test,build,"");ObjectNode stale=request(initial,"当前完整内容准确，我确认通过","approve");
            ObjectNode revise=request(initial,"不通过：补充适用边界","reject");revise.put("reason","补充适用边界");
            post(test,build,revise,202);ready(test,build,revision(initial).path("id").asText());
            int messages=count(test,"ep_message",build,"role='admin'");var repeated=test.request("POST","/builds/"+build+"/messages",revise);
            assertTrue(Set.of(200,202).contains(repeated.status()),repeated.body().toString());assertEquals(messages,count(test,"ep_message",build,"role='admin'"));
            post(test,build,revise.deepCopy().put("content","different same operation"),409);
            post(test,build,stale,409);assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
        }
    }

    @Test void modelApprovalCannotInventMissingPresentationOrWrongDigest() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.maliciousApproval=true;
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();ObjectNode state=ready(test,build,"");
            ObjectNode missing=request(state,"当前完整内容准确，我确认通过",null);missing.remove("reviewContext");
            postAcceptedOrConflict(test,build,missing);settled(test,build);assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
            ObjectNode forged=request(test.snapshot(build),"当前完整内容准确，我确认通过","approve");((ObjectNode)forged.path("reviewContext")).put("contentSha256","0".repeat(64));
            post(test,build,forged,409);assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
        }
    }

    @Test void foreignGeneratedSourceFailsWithoutSealingOrApproving() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.invalidSource=true;
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();
            await(test,build,s->s.path("jobs").findValuesAsText("status").contains("failed"));
            assertEquals(0,count(test,"ep_review",build,"true"));
            assertEquals(0,count(test,"ep_artifact_revision",build,"generation_status='succeeded' AND artifact_id IN (SELECT id FROM ep_artifact WHERE kind='book_summary')"));
            assertEquals("active",test.snapshot(build).path("status").asText());
        }
    }

    @Test void cancellationDiscardsModelOutputEvenWhenProviderIgnoresCancellation() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.block("generate","book_summary");
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();assertTrue(model.entered.await(15,TimeUnit.SECONDS));
            post(test,build,request(test.snapshot(build),"取消构建","cancel"),202);
            await(test,build,s->s.path("status").asText().equals("cancelled"));model.release();settled(test,build);
            assertEquals(0,count(test,"ep_artifact_revision",build,"generation_status='succeeded' AND artifact_id IN (SELECT id FROM ep_artifact WHERE kind='book_summary')"));
            postAcceptedOrConflict(test,build,request(test.snapshot(build),"继续构建","resume"));settled(test,build);
            assertEquals("cancelled",test.snapshot(build).path("status").asText(),"cancelled builds cannot resurrect");
        }
    }

    @Test void restartAndSecondInstanceReadSameDurableApprovalAndContinueWithoutLegacyTrial() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();ObjectNode summary=ready(test,build,"");approve(test,build,summary);
            ObjectNode specialist=ready(test,build,revision(summary).path("id").asText());String id=revision(specialist).path("id").asText();
            test.service.close();ProductionService restored=test.newService();java.net.URI second=test.serve(restored);restored.start();
            var view=test.request(second,"GET","/builds/"+build+"/snapshot",null);assertEquals(200,view.status());
            assertEquals(id,revision(view.body()).path("id").asText());assertEquals(1,count(test,"ep_review",build,"decision='approve'"));
            assertEquals(specialist.path("phase"),view.body().path("phase"));
            test.base=second;approve(test,build,view.body());ready(test,build,id);
            assertEquals(2,count(test,"ep_review",build,"decision='approve'"));
        }
    }

    @Test void requiredClarificationBlocksFullPromptApprovalUntilAdministratorResolvesIt() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.clarifyFirstAgent=true;
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();ObjectNode summary=ready(test,build,"");approve(test,build,summary);
            ObjectNode agent=ready(test,build,revision(summary).path("id").asText());String id=revision(agent).path("id").asText();
            assertTrue(agent.path("clarifications").findValuesAsText("status").contains("open"));
            postAcceptedOrConflict(test,build,request(agent,"当前完整内容准确，我确认通过","approve"));settled(test,build);
            assertEquals(1,count(test,"ep_review",build,"decision='approve'"),"unresolved expert cannot be approved");
            post(test,build,request(test.snapshot(build),"仅用于内部汇报",null),202);
            ObjectNode clarified=ready(test,build,id);
            assertEquals("pending",revision(clarified).path("reviewStatus").asText());
            assertTrue(revision(clarified).path("systemPrompt").asText().contains("仅用于内部汇报"),"clarification must change the full prompt, not only close a question");
            assertTrue(request(clarified,"当前完整内容准确，我确认通过","approve").has("reviewContext"),"new full prompt must be presented again");
            assertEquals(0,count(test,"ep_clarification",build,"status='open'"));
            approve(test,build,clarified);ready(test,build,revision(clarified).path("id").asText());
            assertEquals(2,count(test,"ep_review",build,"decision='approve'"));
        }
    }

    @Test void changingOneApprovedSpecialistPreservesSiblingAndInvalidatesOnlyDependentResults() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();ObjectNode s=ready(test,build,"");
            ObjectNode first=null,second=null;
            for(int i=0;i<4;i++) {
                if(i==1)first=s.deepCopy();if(i==2)second=s.deepCopy();
                String previous=revision(s).path("id").asText();approve(test,build,s);s=ready(test,build,previous);
            }
            // Summary, both specialists and fallback approved; current router has been generated.
            assertEquals("router",s.path("phase").asText());
            ObjectNode edit=request(first,"修改：表达专家补充证据不足先追问",null);edit.put("expectedLockVersion",s.path("lockVersion").asText());
            post(test,build,edit,202);ObjectNode revised=ready(test,build,revision(s).path("id").asText());
            revised=await(test,build,x->isReady(x)&&current(x).path("id").asText().equals(edit.path("reviewContext").path("artifactId").asText())&&revision(x).path("revisionNo").asInt()==2);
            String sibling=current(second).path("id").asText();JsonNode siblingNow=null,fallback=null,router=null;
            for(JsonNode a:revised.path("artifacts")){if(a.path("id").asText().equals(sibling))siblingNow=a;if(a.path("agentRole").asText().equals("fallback"))fallback=a;if(a.path("agentRole").asText().equals("router"))router=a;}
            assertNotNull(siblingNow);assertEquals(revision(second).path("id"),siblingNow.path("currentRevision").path("id"));
            assertEquals("completed",siblingNow.path("currentRevision").path("reviewStatus").asText());assertEquals("current",siblingNow.path("dependencyState").asText());
            assertNotNull(fallback);assertNotNull(router);assertEquals("stale",fallback.path("dependencyState").asText());assertEquals("stale",router.path("dependencyState").asText());
            assertEquals("pending",revision(revised).path("reviewStatus").asText());assertEquals("active",revised.path("status").asText());
        }
    }

    @Test void sourceEndpointEnforcesSelectedVersionsAndCodePointOffsets() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();ObjectNode summary=ready(test,build,"");
            String chunk=revision(summary).path("sources").get(0).path("chunkId").asText();
            var whole=test.request("GET","/builds/"+build+"/sources/"+chunk,null);assertEquals(200,whole.status());
            var part=test.request("GET","/builds/"+build+"/sources/"+chunk+"?startOffset=1&endOffset=4",null);
            String text=whole.body().path("text").asText();assertEquals(text.substring(text.offsetByCodePoints(0,1),text.offsetByCodePoints(0,4)),part.body().path("text").asText());
            assertEquals(422,test.request("GET","/builds/"+build+"/sources/"+chunk+"?startOffset=9&endOffset=2",null).status());
            assertEquals(404,test.request("GET","/builds/"+build+"/sources/"+UUID.randomUUID(),null).status());
        }
    }

    @Test void configuredRedisOutageDoesNotCallModelAndKeepsDurableWorkQueued() throws Exception {
        int unavailable;try(var socket=new java.net.ServerSocket(0)){unavailable=socket.getLocalPort();}
        var model=new ProductionTestServer.ControlledModel();
        try(var test=new ProductionTestServer(temporary,model,new ProductionRedis("redis://127.0.0.1:"+unavailable,""))) {
            String build=create(test);test.service.start();
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(System.nanoTime()<deadline&&count(test,"ep_job",build,"attempt>0")==0)Thread.sleep(50);
            Thread.sleep(300);
            assertTrue(model.inputs.isEmpty(),"configured quota outage must not invoke model adapter");
            assertEquals(0,count(test,"ep_job",build,"status='failed'"),"rate-limit outage is queued waiting, not failed generation");
            assertEquals(0,count(test,"ep_review",build,"true"));
            assertEquals(200,test.request("GET","/builds/"+build+"/events?afterSeq=0",null).status(),"PG event reads survive Redis outage");
            assertEquals("active",test.snapshot(build).path("status").asText());
        }
    }

    @Test void secondInstanceTakesExpiredLeaseAndLateOldWorkerCannotOverwriteItsResult() throws Exception {
        var model=new ProductionTestServer.ControlledModel();model.block("generate","book_summary");
        try(var test=new ProductionTestServer(temporary,model)) {
            String build=create(test);test.service.start();assertTrue(model.entered.await(15,TimeUnit.SECONDS));
            // Fault injection ONLY in this test schema: emulate elapsed lease during a disconnected worker.
            test.db.transaction(c->ProductionDatabase.execute(c,"UPDATE ep_job SET lease_until=now()-interval '1 second' WHERE build_id=? AND kind='generate_summary' AND status='running'",build));
            ProductionService second=test.newService();java.net.URI endpoint=test.serve(second);second.start();
            ObjectNode result=ready(test,build,"");String revisionId=revision(result).path("id").asText();String hash=revision(result).path("contentSha256").asText();
            var other=test.request(endpoint,"GET","/builds/"+build+"/snapshot",null);assertEquals(200,other.status());assertEquals(hash,revision(other.body()).path("contentSha256").asText());
            model.release();settled(test,build);
            assertEquals(revisionId,revision(test.snapshot(build)).path("id").asText());assertEquals(hash,revision(test.snapshot(build)).path("contentSha256").asText());
            assertEquals(1,count(test,"ep_artifact_revision",build,"generation_status='succeeded' AND artifact_id IN (SELECT id FROM ep_artifact WHERE kind='book_summary')"));
            assertTrue(count(test,"ep_job",build,"kind='generate_summary' AND lease_epoch>=2 AND status='succeeded'")>0,"new owner epoch must complete reclaimed job");
            assertEquals(0,count(test,"ep_review",build,"true"));
        }
    }

    @Test void summaryRevisionWithSameSpecialistKeyUpdatesTheNextExpertPlan() throws Exception {
        try(var test=new ProductionTestServer(temporary)) {
            String build=create(test);test.service.start();ObjectNode summary=ready(test,build,"");approve(test,build,summary);
            ObjectNode specialist=ready(test,build,revision(summary).path("id").asText());
            ObjectNode revise=request(summary,"修改：表达专家只处理技术汇报",null);revise.put("expectedLockVersion",specialist.path("lockVersion").asText());
            post(test,build,revise,202);
            ObjectNode revisedSummary=await(test,build,x->isReady(x)&&current(x).path("kind").asText().equals("book_summary")&&revision(x).path("revisionNo").asInt()==2);
            assertEquals("只处理技术汇报",revision(revisedSummary).path("body").path("specialists").get(0).path("responsibility").asText());
            approve(test,build,revisedSummary);ObjectNode revisedExpert=ready(test,build,revision(revisedSummary).path("id").asText());
            assertEquals("specialists",revisedExpert.path("phase").asText());
            assertEquals("只处理技术汇报",revision(revisedExpert).path("body").path("responsibility").asText(),"same key must not keep old generator plan");
            assertEquals("pending",revision(revisedExpert).path("reviewStatus").asText());
        }
    }

    static String create(ProductionTestServer test)throws Exception {String id=UUID.randomUUID().toString();postCreate(test,id,test.createRequest(),202);return id;}
    static void postCreate(ProductionTestServer test,String id,ObjectNode req,int status)throws Exception {var out=test.request("PUT","/builds/"+id,req);assertEquals(status,out.status(),out.body().toString());}
    static void post(ProductionTestServer test,String id,ObjectNode req,int status)throws Exception {var out=test.request("POST","/builds/"+id+"/messages",req);assertEquals(status,out.status(),out.body().toString());}
    static void postAcceptedOrConflict(ProductionTestServer test,String id,ObjectNode req)throws Exception {var out=test.request("POST","/builds/"+id+"/messages",req);assertTrue(Set.of(200,202,409,422).contains(out.status()),out.body().toString());}
    static ObjectNode current(ObjectNode snapshot) {return (ObjectNode)snapshot.path("currentArtifact");}
    static ObjectNode revision(ObjectNode snapshot) {return (ObjectNode)snapshot.path("currentArtifact").path("currentRevision");}
    static boolean isReady(ObjectNode s) {return s.path("currentArtifact").path("currentRevision").path("generationStatus").asText().equals("succeeded");}
    static ObjectNode ready(ProductionTestServer test,String build,String previous)throws Exception {return await(test,build,s->isReady(s)&&!revision(s).path("id").asText().equals(previous));}
    static ObjectNode await(ProductionTestServer test,String build,Predicate<ObjectNode> condition)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(25).toNanos();ObjectNode state=null;
        do {state=test.snapshot(build);if(condition.test(state))return state;Thread.sleep(50);}while(System.nanoTime()<end);
        throw new AssertionError("Timed out: phase="+state.path("phase")+" status="+state.path("status")+" jobs="+state.path("jobs"));
    }
    static void settled(ProductionTestServer test,String build)throws Exception {await(test,build,s->{for(JsonNode j:s.path("jobs"))if(Set.of("queued","running").contains(j.path("status").asText()))return false;return true;});}
    static int count(ProductionTestServer t,String table,String id,String condition)throws Exception {return t.db.read(c->ProductionDatabase.one(c,"SELECT count(*) AS n FROM "+table+" WHERE build_id=? AND "+condition,id)).path("n").asInt();}
    static ObjectNode request(ObjectNode state,String content,String action) {
        ObjectNode request=Json.object().put("clientRequestId",UUID.randomUUID().toString()).put("content",content)
            .put("expectedLockVersion",state.path("lockVersion").asText());
        JsonNode current=state.path("currentArtifact").path("currentRevision");JsonNode presentation=null,message=null;
        for(JsonNode m:state.path("messages"))if(m.path("presentation").path("revisionId").asText().equals(current.path("id").asText())&&!current.path("id").asText().isBlank()){presentation=m.path("presentation");message=m;}
        String scope="artifact.full";
        if(presentation!=null){ObjectNode context=((ObjectNode)presentation).deepCopy();context.put("presentedMessageId",message.path("id").asText());request.set("reviewContext",context);request.put("replyToMessageId",message.path("id").asText());scope=context.path("scope").asText();}
        if(action!=null)request.set("action",Json.object().put("type",action).put("scope",scope));return request;
    }
    static void approve(ProductionTestServer test,String build,ObjectNode state)throws Exception {
        ObjectNode request=request(state,"当前完整内容准确，我确认通过","approve");assertTrue(request.has("reviewContext"),"server must publish presentation of exact complete current content");post(test,build,request,202);
    }
}
