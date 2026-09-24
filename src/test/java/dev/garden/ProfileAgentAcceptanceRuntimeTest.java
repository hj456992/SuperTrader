package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.dsh.contract.llm.ModelRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** QA owns assertions and fixtures; only reuses backend's real DSH plugin bootstrap. */
class ProfileAgentAcceptanceRuntimeTest {
    static final String BOOK="K-qa-method-0";
    static ObjectNode output(String summary,String own,String counter,boolean book) {
        var out=Store.JSON.createObjectNode().put("summary",summary);
        var f=out.putArray("facets").addObject().put("category","当前表达").put("text",summary).put("kind","inferred");
        f.putArray("evidenceIds").add(own);f.putArray("knowledgeIds");if(book)f.withArray("knowledgeIds").add(BOOK);
        f.putArray("counterEvidenceIds");if(counter!=null)f.withArray("counterEvidenceIds").add(counter);
        out.putArray("uncertainties").add("仅限虚构测试材料，长期偏好未知。");return out;
    }
    static String observations(Map<String,Object> request) {
        var out=new StringBuilder();collect(Store.JSON.valueToTree(request.get("messages")),out);return out.toString();
    }
    static void collect(JsonNode node,StringBuilder out) {
        if(node.isObject() && (node.path("type").asText().equals("tool-result")||node.path("role").asText().equals("tool")))out.append(node);
        else if(node.isContainerNode())node.forEach(n->collect(n,out));
    }
    static RanchAnalyzer analyzer(ProfileRuntimeTest.Harness h,Repo repo) {
        return new RanchAnalyzer(h.context.require(ModelRegistry.KEY),repo,new RanchKnowledge(repo),new ProfileRuntime(h.context));
    }
    static void awaitTerminal(RanchAnalyzer a)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(Set.of("running","cancelling").contains(a.status().get("status"))&&System.nanoTime()<end)Thread.sleep(5);
        assertFalse(Set.of("running","cancelling").contains(a.status().get("status")),a.status().toString());
    }
    static void await(CountDownLatch latch)throws Exception {assertTrue(latch.await(15,TimeUnit.SECONDS),"controlled boundary not reached");}
    static void lateRelease(CountDownLatch entered,CountDownLatch release) {
        entered.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(release.getCount()>0&&System.nanoTime()<end)try{release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
        if(release.getCount()>0)throw new IllegalStateException("QA boundary timed out");
    }
    static class Repo implements RanchRepository {
        ObjectNode state,job=Store.JSON.createObjectNode().put("status","idle");
        final Map<String,ObjectNode> books=new HashMap<>();final List<ObjectNode> jobs=new ArrayList<>();
        int saves;volatile CountDownLatch bookEntered,bookRelease,saveEntered,saveRelease;
        Repo()throws Exception {
            var fixture=ProfileAgentAcceptanceTest.fixture();state=(ObjectNode)fixture.path("state");
            var book=Store.JSON.createObjectNode();book.set("chunks",RanchKnowledge.chunk("qa-method","虚构倾听练习",(ArrayNode)fixture.path("book").path("pages")));books.put("qa-method",book);
        }
        public synchronized ObjectNode read(){return state.deepCopy();}
        public synchronized ObjectNode update(long expected,Consumer<ObjectNode> mutation){if(state.path("revision").asLong()!=expected)throw new IllegalStateException("stale");if(saveEntered!=null)lateRelease(saveEntered,saveRelease);var next=state.deepCopy();mutation.accept(next);next.put("revision",expected+1);state=next;saves++;return read();}
        public synchronized ObjectNode readJob(){return job.deepCopy();}
        public synchronized void writeJob(ObjectNode next){job=next.deepCopy();jobs.add(next.deepCopy());}
        public ObjectNode book(String id){if(bookEntered!=null)lateRelease(bookEntered,bookRelease);return books.get(id).deepCopy();}
        public void putBook(String id,ObjectNode value){books.put(id,value.deepCopy());}
        public void deleteBook(String id){books.remove(id);}
    }

    @Test void actualToolObservationChangesSavedOutputForBothSelfAndPerson()throws Exception {
        for(String target:List.of("person-lan","self")) {
            var summaries=new ArrayList<String>();
            for(boolean hasObservation:List.of(false,true)) {
                var repo=new Repo();boolean self=target.equals("self");
                String own=self?"M-self-direct":"M-lan-1",observed=self?"M-me-in-yu":"M-lan-counter";
                String phrase=self?"我临时有事会提前说明。":"但这个周末我只想休息，请不要替我确认散步。";
                String query=self?"临时":"休息";
                for(var p:repo.state.path("people"))for(var m:p.path("materials"))if(m.path("id").asText().equals(observed)&&!hasObservation)((ObjectNode)m).put("text","QA没有该观察的对照材料");
                var subject=RanchData.target(repo.state,target);
                for(var m:subject.path("materials"))if(m.path("id").asText().equals(own))((ObjectNode)m).put("at","2026-01-07T00:00:00Z");
                for(int i=0;i<160;i++)subject.withArray("materials").addObject().put("id","qa-padding-"+i).put("speaker",self?"me":"them").put("text","QA中性片段").put("at","2026-01-06T00:00:00Z");
                var calls=new AtomicInteger();
                try(var h=new ProfileRuntimeTest.Harness(request->{
                    int step=calls.incrementAndGet();
                    if(step==1){assertFalse(request.get("messages").toString().contains(phrase),"observation must be absent from initial bounded input");return ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}");}
                    String obs=observations(request);assertTrue(obs.contains("不把一般偏好当作本次同意"),"book result must reach model: "+obs);
                    if(step==2)return ProfileRuntimeTest.tool("chat_search","{\"query\":\""+query+"\"}");
                    assertEquals(3,step);assertTrue(obs.contains("newAllowedProfileEvidenceIds"));
                    assertFalse(obs.contains("M-yu-1"));
                    boolean changed=obs.contains(phrase);assertEquals(hasObservation,changed);
                    return ProfileRuntimeTest.answer(output(changed?(self?"临时变更时会提前说明。":"本周末希望休息，不能视为已同意散步。"):(self?"只知道喜欢提前确认安排。":"一般愿意散步，本次安排尚未确认。"),own,changed?observed:null,true).toString());
                });var a=analyzer(h,repo)) {
                    String id=a.start(repo.read(),target,"profile","").get("id").toString();awaitTerminal(a);
                    assertEquals("done",a.status().get("status"),a.status().toString());assertEquals(3,calls.get());assertEquals(1,repo.saves);
                    var saved=RanchData.target(repo.read(),target).path("profile");summaries.add(saved.path("summary").asText());
                    assertEquals(id,saved.path("runId").asText());assertEquals("used",saved.path("knowledgeStatus").asText());assertEquals(BOOK,saved.path("knowledge").get(0).path("id").asText());
                    assertEquals(8,repo.read().path("revision").asInt());assertEquals(7,saved.path("basedOnRevision").asInt());
                    assertFalse(repo.jobs.toString().contains(phrase),"job events must not expose original chat");
                    var phases=new HashSet<String>();
                    for(var job:repo.jobs) {
                        phases.add(job.path("phase").asText());assertTrue(job.path("events").size()<=32);assertTrue(job.path("step").asInt()<=6);
                        int seq=0;for(var event:job.path("events")){assertTrue(event.path("seq").asInt()>seq);seq=event.path("seq").asInt();assertEquals("phase",event.path("type").asText());}
                    }
                    assertTrue(phases.containsAll(Set.of("knowledge","evidence","validating","saving","finished")));
                }
            }
            assertNotEquals(summaries.get(0),summaries.get(1),"changing actual observations must change committed content");
        }
    }

    @Test void emptyDisabledAndUnmatchedLibrariesSaveLimitedResultsWithDistinctStatuses()throws Exception {
        for(String mode:List.of("empty","disabled","unmatched")) {
            var repo=new Repo();if(mode.equals("empty"))repo.state.putArray("library");if(mode.equals("disabled"))((ObjectNode)repo.state.path("library").get(0)).put("enabled",false);
            var calls=new AtomicInteger();
            try(var h=new ProfileRuntimeTest.Harness(r->calls.incrementAndGet()==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"量子黑洞\"}"):ProfileRuntimeTest.answer(output("仅依据当前发言。","M-lan-1",null,false).toString()));var a=analyzer(h,repo)) {
                a.start(repo.read(),"person-lan","profile","");awaitTerminal(a);assertEquals("done",a.status().get("status"));
                var saved=repo.read().path("people").get(0).path("profile");assertEquals(mode.equals("unmatched")?"no_match":"empty_library",saved.path("knowledgeStatus").asText());
                assertTrue(saved.path("knowledge").isEmpty());assertTrue(saved.path("uncertainties").size()>=2);assertEquals(2,calls.get());
            }
        }
    }

    @Test void invalidPersonAndNeverReadBookIdsNeverReachPersistence()throws Exception {
        for(String invalid:List.of("other-person","book-never-read","other-counter","known-but-unread-chat")) {
            var repo=new Repo();var calls=new AtomicInteger();var result=output("拒绝保存。","M-lan-1",null,true);var facet=(ObjectNode)result.path("facets").get(0);
            if(invalid.equals("other-person"))facet.withArray("evidenceIds").add("M-yu-1");
            if(invalid.equals("book-never-read"))facet.putArray("knowledgeIds").add("K-qa-method-1"); // Exists in library, query below reads only page 1.
            if(invalid.equals("other-counter"))facet.withArray("counterEvidenceIds").add("M-me-in-lan");
            if(invalid.equals("known-but-unread-chat")) {
                var person=RanchData.target(repo.state,"person-lan");
                ((ObjectNode)person.path("materials").get(0)).put("at","2026-01-07T00:00:00Z");
                for(int i=0;i<160;i++)person.withArray("materials").addObject().put("id","unread-padding-"+i).put("speaker","them").put("text","QA中性片段").put("at","2026-01-06T00:00:00Z");
                assertFalse(RanchData.profileInput(RanchData.input(repo.state,"person-lan")).toString().contains("M-lan-counter"));
                facet.withArray("evidenceIds").add("M-lan-counter");
            }
            try(var h=new ProfileRuntimeTest.Harness(r->calls.incrementAndGet()==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"冲突证据\"}"):ProfileRuntimeTest.answer(result.toString()));var a=analyzer(h,repo)) {
                a.start(repo.read(),"person-lan","profile","");awaitTerminal(a);assertEquals("error",a.status().get("status"),invalid);assertEquals(0,repo.saves);assertEquals(7,repo.read().path("revision").asInt());
            }
        }
    }

    @Test void cancelAtFirstResponseToolAndFinalResponsePreventsLateCommits()throws Exception {
        for(String boundary:List.of("first-model","book-tool","final-model")) {
            var repo=new Repo();repo.state.withObject("self").set("profile",output("此前有效画像","M-self-direct",null,false));
            var before=repo.read();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            if(boundary.equals("book-tool")){repo.bookEntered=entered;repo.bookRelease=release;}
            try(var h=new ProfileRuntimeTest.Harness(r->{int step=calls.incrementAndGet();return Flux.defer(()->{
                if(boundary.equals("first-model")&&step==1||boundary.equals("final-model")&&step==2)lateRelease(entered,release);
                return step==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}"):ProfileRuntimeTest.answer(output("迟到覆盖","M-self-direct",null,true).toString());
            });});var a=analyzer(h,repo)) {
                String id=a.start(repo.read(),"self","profile","").get("id").toString();await(entered);
                a.cancel("obsolete-run");assertEquals("running",a.status().get("status"));
                a.cancel(id);a.cancel(id);assertThrows(IllegalStateException.class,()->a.start(repo.read(),"self","profile",""));
                release.countDown();awaitTerminal(a);assertEquals("cancelled",a.status().get("status"),boundary);assertEquals(before,repo.read(),boundary);assertEquals(0,repo.saves);
                if(boundary.equals("book-tool"))assertEquals(1,calls.get(),"cancelled tool must not trigger next model call");
            }finally{release.countDown();}
        }
    }

    @Test void businessRevisionChangeRejectsCompletedOldInputButProgressDoesNotMutateState()throws Exception {
        var repo=new Repo();var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var h=new ProfileRuntimeTest.Harness(r->{if(calls.incrementAndGet()==1)return ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}");return Flux.defer(()->{lateRelease(entered,release);return ProfileRuntimeTest.answer(output("旧输入结果","M-self-direct",null,true).toString());});});var a=analyzer(h,repo)) {
            a.start(repo.read(),"self","profile","");await(entered);assertEquals(7,repo.read().path("revision").asInt());assertEquals(0,repo.saves);
            repo.update(7,s->s.withObject("self").put("about","新业务材料"));var edited=repo.read();release.countDown();awaitTerminal(a);
            assertEquals("error",a.status().get("status"));assertEquals(edited,repo.read());assertEquals(1,repo.saves);
        }finally{release.countDown();}
    }

    @Test void restartingBothActiveStatusesPreservesLegacyProfileAndBusinessRevision()throws Exception {
        for(String status:List.of("running","cancelling")) {
            var repo=new Repo();repo.state.withObject("self").set("profile",ProfileAgentAcceptanceTest.fixture().path("legacyProfile"));var before=repo.read();repo.job.put("id","old-run").put("status",status).put("targetId","self");
            try(var a=new RanchAnalyzer(null,repo,new RanchKnowledge(repo),null)) {
                assertEquals("interrupted",a.status().get("status"));assertEquals("interrupted",repo.readJob().path("status").asText());assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }
        }
    }

    @Test void deletingBookRevokesQuotesAcrossBothSubjectsAndHistory()throws Exception {
        var repo=new Repo();var chunks=(ArrayNode)repo.book("qa-method").path("chunks");
        for(String target:List.of("self","person-lan")) {
            var p=RanchData.target(repo.state,target);var result=output("旧认识",target.equals("self")?"M-self-direct":"M-lan-1",null,true);result.set("knowledge",chunks.deepCopy());p.set("profile",result);p.putArray("analyses").add(result.deepCopy());
        }
        new RanchKnowledge(repo).command("knowledge-delete",Store.JSON.createObjectNode().put("revision",7).put("id","qa-method"));
        for(String target:List.of("self","person-lan")) {var p=RanchData.target(repo.read(),target);assertFalse(p.path("profile").toString().contains(BOOK));assertFalse(p.path("analyses").toString().contains(BOOK));}
        assertFalse(repo.books.containsKey("qa-method"));
    }

    @Test void malformedOrFailedModelNeverOverwritesPreviousProfile()throws Exception {
        for(boolean malformed:List.of(false,true)) {
            var repo=new Repo();repo.state.withObject("self").set("profile",output("旧画像","M-self-direct",null,false));var before=repo.read();var calls=new AtomicInteger();
            try(var h=new ProfileRuntimeTest.Harness(r->{if(calls.incrementAndGet()==1)return ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}");return malformed?ProfileRuntimeTest.answer("{\"summary\":"):Flux.error(new IllegalStateException("QA_MODEL_FAILED"));});var a=analyzer(h,repo)) {
                a.start(repo.read(),"self","profile","");awaitTerminal(a);assertEquals("error",a.status().get("status"));assertEquals(before,repo.read());assertEquals(0,repo.saves);assertFalse(a.status().toString().contains("QA_MODEL_FAILED"));
            }
        }
    }
    @Test void existingStrategyUsesNewGoalAndBothSpeakersWithoutRewritingProfile()throws Exception {
        var repo=new Repo();var p=RanchData.target(repo.state,"person-lan");
        var prior=ProfileAgentAcceptanceTest.fixture().path("legacyProfile").deepCopy();p.set("profile",prior);
        p.put("goalType","romance").put("goal","QA进一步了解");
        var calls=new AtomicInteger();
        try(var h=new ProfileRuntimeTest.Harness(r->{
            calls.incrementAndGet();String payload=r.get("messages").toString();
            assertTrue(payload.contains("QA进一步了解"));assertTrue(payload.contains("M-lan-1"));assertTrue(payload.contains("M-self-direct"));
            var strategy=Store.JSON.createObjectNode().put("overview","依据双方已知材料谨慎询问").put("reply","").put("why","对方提及散步，本人喜欢提前确认。");
            strategy.putArray("steps").addObject().put("title","确认意愿").put("detail","先确认是否方便，不替对方作承诺。");
            strategy.putArray("evidenceIds").add("M-lan-1").add("M-self-direct");strategy.putArray("knowledgeIds");
            return ProfileRuntimeTest.answer(strategy.toString());
        });var a=analyzer(h,repo)) {
            a.start(repo.read(),"person-lan","strategy","想了解周末安排");awaitTerminal(a);
            assertEquals("done",a.status().get("status"));assertEquals(1,calls.get());assertEquals(1,repo.saves);
            var saved=RanchData.target(repo.read(),"person-lan");assertEquals(prior,saved.path("profile"));
            assertEquals(1,saved.path("strategies").size());assertEquals("想了解周末安排",saved.path("strategies").get(0).path("situation").asText());
        }
    }

    @Test void noChunkHangingModelCancelsWithinBoundWithoutWaitingForProvider()throws Exception {
        var repo=new Repo();var entered=new CountDownLatch(1);var streamCancelled=new CountDownLatch(1);
        var provider=new java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.FluxSink<Map<String,Object>>>();
        try(var h=new ProfileRuntimeTest.Harness(r->Flux.<Map<String,Object>>create(sink->{sink.onCancel(streamCancelled::countDown);provider.set(sink);entered.countDown();}));var a=analyzer(h,repo)) {
            a.start(repo.read(),"self","profile","");await(entered);long started=System.nanoTime();
            a.cancel();
            try {
                long end=started+TimeUnit.SECONDS.toNanos(3);
                while(!"cancelled".equals(a.status().get("status"))&&System.nanoTime()<end)Thread.sleep(5);
                assertEquals("cancelled",a.status().get("status"),"No-chunk provider must release without emitting or completing");
                assertTrue(streamCancelled.await(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS),"Cancellation must reach provider subscription within the same bound");
                assertEquals(0,repo.saves);assertEquals(7,repo.read().path("revision").asInt());
            } finally { if(provider.get()!=null)provider.get().complete(); }
        }
    }

    @Test void saveThatAlreadyOwnsCommitBarrierRemainsDoneAfterConcurrentCancel()throws Exception {
        var repo=new Repo();repo.saveEntered=new CountDownLatch(1);repo.saveRelease=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var h=new ProfileRuntimeTest.Harness(r->calls.incrementAndGet()==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}"):ProfileRuntimeTest.answer(output("已保存","M-self-direct",null,true).toString()));var a=analyzer(h,repo)) {
            String id=a.start(repo.read(),"self","profile","").get("id").toString();await(repo.saveEntered);
            var attempted=new CountDownLatch(1);var returned=new CountDownLatch(1);
            var cancel=new Thread(()->{attempted.countDown();a.cancel(id);returned.countDown();},"qa-cancel-during-save");cancel.start();await(attempted);
            try {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(cancel.getState()!=Thread.State.BLOCKED&&returned.getCount()>0&&System.nanoTime()<end)Thread.sleep(2);
                assertEquals(Thread.State.BLOCKED,cancel.getState(),"Cancel must serialize with the in-progress commit");
            } finally {repo.saveRelease.countDown();}
            await(returned);cancel.join(1000);awaitTerminal(a);
            assertEquals("done",a.status().get("status"));assertEquals(1,repo.saves);
            assertEquals("已保存",repo.read().path("self").path("profile").path("summary").asText());
            a.cancel(id);assertEquals("done",a.status().get("status"),"completed commit must not be called cancelled");
        }finally{repo.saveRelease.countDown();}
    }

    @Test void repeatedToolsStopAtSixModelCallsAndNeverCommitPartialResult()throws Exception {
        var repo=new Repo();var before=repo.read();var calls=new AtomicInteger();
        try(var h=new ProfileRuntimeTest.Harness(r->{calls.incrementAndGet();return ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}");});var a=analyzer(h,repo)) {
            a.start(repo.read(),"self","profile","");awaitTerminal(a);
            assertEquals("error",a.status().get("status"));assertEquals(6,calls.get());assertTrue(a.status().get("error").toString().contains("6"));
            assertEquals(before,repo.read());assertEquals(0,repo.saves);
        }
    }

    @Test void deadlineCancelsSilentProviderAndNeverCommits()throws Exception {
        var repo=new Repo();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        var provider=new java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.FluxSink<Map<String,Object>>>();
        try(var h=new ProfileRuntimeTest.Harness(r->Flux.<Map<String,Object>>create(sink->{sink.onCancel(cancelled::countDown);provider.set(sink);entered.countDown();}));
            var a=new RanchAnalyzer(h.context.require(ModelRegistry.KEY),repo,new RanchKnowledge(repo),new ProfileRuntime(h.context,Duration.ofSeconds(2)))) {
            a.start(repo.read(),"self","profile","");
            try {
                await(entered);awaitTerminal(a);assertEquals("error",a.status().get("status"));assertTrue(a.status().get("error").toString().contains("时限"));
                assertTrue(cancelled.await(2,TimeUnit.SECONDS),"deadline must release actual provider source subscription");assertEquals(0,repo.saves);
            } finally {if(provider.get()!=null)provider.get().complete();}
        }
    }

}
