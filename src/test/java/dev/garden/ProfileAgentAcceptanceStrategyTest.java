package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import dev.dsh.contract.llm.ModelRegistry;
import dev.dsh.plugin.model.registry.application.DefaultModelRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent G01 gates through the real registry and analyzer, using only fictional data. */
@Timeout(value=12,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class ProfileAgentAcceptanceStrategyTest {
    static final String TARGET="person-lan";
    static class Repo extends ProfileAgentAcceptanceRuntimeTest.Repo {
        final List<String> trace=new CopyOnWriteArrayList<>();
        Repo()throws Exception {
            state.putArray("library");
            var person=RanchData.target(state,TARGET);
            person.set("profile",ProfileAgentAcceptanceTest.fixture().path("legacyProfile"));
            person.withObject("profile").put("stale",false);
            person.putArray("strategies").addObject().put("id","qa-old-strategy").put("overview","旧攻略保持原样");
            state.withObject("self").putNull("profile");
        }
        @Override public synchronized void writeJob(ObjectNode next){super.writeJob(next);trace.add("job:"+next.path("status").asText()+":"+next.path("phase").asText()+":"+next.path("step").asInt());}
    }
    static DefaultModelRegistry registry(ModelRegistry.Adapter adapter){var r=new DefaultModelRegistry();r.registerAdapter("deepseek",adapter,Map.of(),Map.of());return r;}
    static ModelRegistry.AdapterCall adapter(Function<Map<String,Object>,Flux<Map<String,Object>>> stream){return new ModelRegistry.AdapterCall(){
        public Map<String,Object> model(){return Map.of();}
        public Flux<Map<String,Object>> stream(Map<String,Object> request){return stream.apply(request);}
    };}
    static String strategy(String evidence){return "{\"overview\":\"仅限这次安排\",\"steps\":[{\"title\":\"询问\",\"detail\":\"先询问是否方便\"},{\"title\":\"等待\",\"detail\":\"尊重拒绝，不催促\"}],\"reply\":\"你愿意聊聊安排吗？\",\"why\":\"尚不了解长期习惯\",\"evidenceIds\":[\""+evidence+"\"],\"knowledgeIds\":[]}";}
    static Flux<Map<String,Object>> answer(){return ProfileRuntimeTest.answer(strategy("M-lan-1"));}
    static void stopped(RanchAnalyzer a)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Set.of("running","cancelling").contains(a.status().get("status"))&&System.nanoTime()<deadline)Thread.sleep(5);
        assertFalse(Set.of("running","cancelling").contains(a.status().get("status")),"worker must stop without emergency fixture completion: "+a.status());
    }
    static void cleanup(RanchAnalyzer a,AtomicReference<FluxSink<Map<String,Object>>> sink)throws Exception {a.cancel();if(sink.get()!=null)sink.get().complete();stopped(a);}

    @Test void silentSourceIsCancelledBeforePersistedTerminalAndNextRunStillWorks()throws Exception {
        var repo=new Repo();var before=repo.read();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        var sink=new AtomicReference<FluxSink<Map<String,Object>>>();var calls=new AtomicInteger();
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{
            if(calls.incrementAndGet()>1)return answer();
            return Flux.create(source->{source.onCancel(()->{repo.trace.add("source-cancel");cancelled.countDown();});sink.set(source);entered.countDown();});
        })));
        try(var a=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            try {
                String old=a.start(repo.read(),TARGET,"strategy","虚构安排").get("id").toString();
                assertTrue(entered.await(2,TimeUnit.SECONDS));a.cancel(old);
                assertTrue(cancelled.await(2,TimeUnit.SECONDS),"original provider must receive cancel before cleanup");
                stopped(a);assertEquals("cancelled",a.status().get("status"));assertEquals(before,repo.read());assertEquals(0,repo.saves);
                assertTrue(repo.trace.indexOf("source-cancel")<repo.trace.indexOf("job:cancelled:finished:1"),repo.trace.toString());
                a.cancel(old);a.cancel(old);
                String current=a.start(repo.read(),TARGET,"strategy","新的虚构安排").get("id").toString();a.cancel(old);stopped(a);
                assertNotEquals(old,current);assertEquals("done",a.status().get("status"));assertEquals(1,repo.saves);assertEquals(2,calls.get());
            }finally{cleanup(a,sink);}
        }
    }

    @Test void cancellationFinishesBeforePreparationCompletesAndLateCallNeverSubscribes()throws Exception {
        var repo=new Repo();var before=repo.read();var preparing=new CountDownLatch(1);
        var delayed=new CompletableFuture<ModelRegistry.AdapterCall>();var preparations=new AtomicInteger();var lateSubscriptions=new AtomicInteger();
        var models=registry((p,m,s)->{if(preparations.incrementAndGet()==1){preparing.countDown();return delayed;}return CompletableFuture.completedFuture(adapter(request->answer()));});
        try(var a=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            try {
                String old=a.start(repo.read(),TARGET,"strategy","虚构安排").get("id").toString();assertTrue(preparing.await(2,TimeUnit.SECONDS));a.cancel(old);
                stopped(a);assertEquals("cancelled",a.status().get("status"));assertFalse(delayed.isDone());assertEquals(before,repo.read());assertEquals(0,repo.saves);
                String current=a.start(repo.read(),TARGET,"strategy","新任务").get("id").toString();stopped(a);var saved=repo.read();
                assertTrue(delayed.complete(adapter(request->Flux.defer(()->{lateSubscriptions.incrementAndGet();return answer();}))));
                assertEquals(0,lateSubscriptions.get());assertEquals(current,a.status().get("id"));assertEquals("done",a.status().get("status"));assertEquals(saved,repo.read());assertEquals(1,repo.saves);
            }finally{a.cancel();delayed.complete(adapter(request->Flux.empty()));stopped(a);}
        }
    }

    @Test void realStrategyStagesAndNewIdentityPreserveOldProfileAndStrategy()throws Exception {
        var repo=new Repo();var before=repo.read();var calls=new AtomicInteger();
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{
            calls.incrementAndGet();var wire=Store.JSON.valueToTree(request.get("messages")).toString();
            assertTrue(wire.contains(RanchData.target(before,TARGET).path("goal").asText()));
            assertTrue(wire.contains("M-self-direct"));assertTrue(wire.contains("M-lan-1"));return answer();
        })));
        try(var a=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            String run=a.start(repo.read(),TARGET,"strategy","虚构安排").get("id").toString();stopped(a);
            assertEquals("done",a.status().get("status"));assertEquals(1,calls.get());assertEquals(1,repo.saves);
            var trace=repo.trace;
            assertTrue(trace.contains("job:running:reasoning:1"),trace.toString());
            assertTrue(trace.indexOf("job:running:reasoning:1")<trace.indexOf("job:running:validating:1"));
            assertTrue(trace.indexOf("job:running:validating:1")<trace.indexOf("job:running:saving:1"));
            var person=RanchData.target(repo.read(),TARGET);var strategies=person.path("strategies");
            assertEquals(before.path("revision").asLong()+1,repo.read().path("revision").asLong());
            assertEquals(RanchData.target(before,TARGET).path("profile"),person.path("profile"));
            assertEquals(RanchData.target(before,TARGET).path("strategies").get(0),strategies.get(0));
            assertEquals(2,strategies.size());assertEquals(run,strategies.get(1).path("runId").asText());
            assertTrue(strategies.get(1).path("basedOnRevision").isIntegralNumber());
            assertEquals(before.path("revision").asLong(),strategies.get(1).path("basedOnRevision").asLong());
        }
    }

    @Test void onlyOneCitationRepairKeepsIntegerBudgetAndSavesOnce()throws Exception {
        var repo=new Repo();var calls=new AtomicInteger();
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{
            assertTrue(Store.JSON.valueToTree(request.get("maxTokens")).isIntegralNumber());assertEquals(6000,((Number)request.get("maxTokens")).intValue());
            return ProfileRuntimeTest.answer(strategy(calls.incrementAndGet()==1?"qa-never-read":"M-lan-1"));
        })));
        try(var a=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {a.start(repo.read(),TARGET,"strategy","虚构安排");stopped(a);assertEquals("done",a.status().get("status"));assertEquals(2,calls.get());assertEquals(1,repo.saves);}
    }
}
