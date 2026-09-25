package dev.garden;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dsh.contract.llm.ModelRegistry;
import dev.dsh.plugin.model.registry.application.DefaultModelRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G01 execution gates promoted from the explicit red reproduction.
 * Run with -Dtest=StrategyExecutionTest. No model network or database.
 * The real registry resolves calls; only the external adapter and repository are synthetic.
 */
@Timeout(value=10, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class StrategyExecutionTest {
    @Test void silentCancelMustReleaseOriginalProviderAndAllowAnotherTask() throws Exception {
        var entered=new CountDownLatch(1);
        var released=new CountDownLatch(1);
        var source=new AtomicReference<FluxSink<Map<String,Object>>>();
        var calls=new AtomicInteger();
        var repo=new StrategyRepository();
        var trace=new CopyOnWriteArrayList<String>();
        repo.onStatus=status->{if(status.equals("cancelled"))trace.add("persisted-cancelled");};
        var models=registry((provider,model,signal)->CompletableFuture.completedFuture(adapter(request->{
            if(calls.incrementAndGet()>1)return answer("P1");
            return Flux.create(sink->{
                sink.onCancel(()->{trace.add("provider-cancel");released.countDown();});
                source.set(sink);
                entered.countDown();
            });
        })));
        try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            try {
                String run=analyzer.start(repo.read(),"person-1","strategy","询问是否想散步").get("id").toString();
                assertTrue(entered.await(2,TimeUnit.SECONDS),"provider was never subscribed");
                analyzer.cancel(run);
                boolean cancelledAtSource=released.await(2,TimeUnit.SECONDS);
                assertEquals(0,repo.saves);
                assertTrue(cancelledAtSource,"Original Flux.create.onCancel was not called within 2s; job="+analyzer.status().get("status"));
                // All release/terminal/restart gates run before emergency source completion.
                awaitStopped(analyzer);
                assertEquals("cancelled",analyzer.status().get("status"));
                assertTrue(trace.indexOf("provider-cancel")<trace.indexOf("persisted-cancelled"),trace.toString());
                assertTrue(repo.read().path("people").get(0).path("strategies").isEmpty());
                assertEquals(0,repo.read().path("revision").asInt());
                analyzer.start(repo.read(),"person-1","strategy","询问是否想散步");
                awaitStopped(analyzer);
                assertEquals("done",analyzer.status().get("status"));
                assertEquals(1,repo.saves);
            } finally {
                // Complete the synthetic source even on a red assertion; never wait 110 seconds.
                analyzer.cancel();
                var sink=source.get();if(sink!=null)sink.complete();
                awaitStopped(analyzer);
            }
        }
    }

    @Test void cancelledPreparationMustNotSubscribeItsLatePreparedCall() throws Exception {
        var preparing=new CountDownLatch(1);
        var prepared=new CompletableFuture<ModelRegistry.AdapterCall>();
        var subscriptions=new AtomicInteger();
        var repo=new StrategyRepository();
        var models=registry((provider,model,signal)->{preparing.countDown();return prepared;});
        try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            try {
                String run=analyzer.start(repo.read(),"person-1","strategy","询问是否想散步").get("id").toString();
                assertTrue(preparing.await(2,TimeUnit.SECONDS));
                analyzer.cancel(run);
                prepared.complete(adapter(request->Flux.defer(()->{
                    subscriptions.incrementAndGet();return answer("P1");
                })));
                awaitStopped(analyzer);
                assertEquals("cancelled",analyzer.status().get("status"));
                assertEquals(0,repo.saves);
                assertEquals(0,subscriptions.get(),"Cancellation was accepted before preparation completed; late call must never subscribe");
            } finally {
                analyzer.cancel();
                prepared.complete(adapter(request->Flux.empty()));
                awaitStopped(analyzer);
            }
        }
    }

    @Test void cancelMustEndLocalPreparationBeforeItsFutureCompletes() throws Exception {
        var preparing=new CountDownLatch(1);
        var prepared=new CompletableFuture<ModelRegistry.AdapterCall>();
        var subscriptions=new AtomicInteger();var repo=new StrategyRepository();
        var models=registry((provider,model,signal)->{preparing.countDown();return prepared;});
        try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            try {
                analyzer.start(repo.read(),"person-1","strategy","询问是否想散步");
                assertTrue(preparing.await(2,TimeUnit.SECONDS));analyzer.cancel();
                awaitStopped(analyzer);
                assertFalse(prepared.isDone(),"Test must not release preparation to obtain the terminal state");
                assertEquals("cancelled",analyzer.status().get("status"));assertEquals(0,repo.saves);
                prepared.complete(adapter(request->Flux.defer(()->{subscriptions.incrementAndGet();return answer("P1");})));
                assertEquals(0,subscriptions.get());
            } finally {
                analyzer.cancel();prepared.complete(adapter(request->Flux.empty()));awaitStopped(analyzer);
            }
        }
    }

    @Test void completedStrategyMustRecordActualReasoningAndValidationPhases() throws Exception {
        var repo=new StrategyRepository();
        var models=registry((provider,model,signal)->CompletableFuture.completedFuture(adapter(request->answer("P1"))));
        try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            analyzer.start(repo.read(),"person-1","strategy","询问是否想散步");awaitStopped(analyzer);
            assertEquals("done",analyzer.status().get("status"));assertEquals(1,repo.saves);
            var recorded=repo.phases();
            assertTrue(recorded.contains("reasoning:1"),"No actual model-call progress: "+recorded);
            assertTrue(recorded.contains("validating:1"),"No validation progress: "+recorded);
            assertTrue(recorded.indexOf("reasoning:1")<recorded.indexOf("validating:1"));
            assertTrue(recorded.indexOf("validating:1")<recorded.indexOf("saving:1"));
        }
    }

    @Test void invalidFirstCitationCanAlreadyBeRepairedByExactlyOneSecondCall() throws Exception {
        var repo=new StrategyRepository();var calls=new AtomicInteger();
        var models=registry((provider,model,signal)->CompletableFuture.completedFuture(adapter(request->{
            assertTrue(Store.JSON.valueToTree(request.get("maxTokens")).isIntegralNumber());
            assertEquals(6000,((Number)request.get("maxTokens")).intValue());
            return answer(calls.incrementAndGet()==1?"unread-id":"P1");
        })));
        try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))) {
            analyzer.start(repo.read(),"person-1","strategy","询问是否想散步");awaitStopped(analyzer);
            assertEquals("done",analyzer.status().get("status"));
            assertEquals(2,calls.get());assertEquals(1,repo.saves);
            assertEquals("P1",repo.read().path("people").get(0).path("strategies").get(0).path("evidenceIds").get(0).asText());
        }
    }

    static DefaultModelRegistry registry(ModelRegistry.Adapter adapter) {
        var registry=new DefaultModelRegistry();registry.registerAdapter("deepseek",adapter,Map.of(),Map.of());return registry;
    }
    static ModelRegistry.AdapterCall adapter(Function<Map<String,Object>,Flux<Map<String,Object>>> stream) {
        return new ModelRegistry.AdapterCall() {
            public Map<String,Object> model(){return Map.of();}
            public Flux<Map<String,Object>> stream(Map<String,Object> request){return stream.apply(request);}
        };
    }
    static Flux<Map<String,Object>> answer(String evidenceId) {
        String text="{\"overview\":\"只基于这次散步意愿\",\"steps\":[{\"title\":\"先询问\",\"detail\":\"询问是否方便，不催促\"},{\"title\":\"留出选择\",\"detail\":\"不愿意就停止邀请\"}],\"reply\":\"你想一起散步吗？\",\"why\":\"尚不了解长期习惯\",\"evidenceIds\":[\""+evidenceId+"\"],\"knowledgeIds\":[]}";
        return ProfileRuntimeTest.answer(text);
    }
    static void awaitStopped(RanchAnalyzer analyzer) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(Set.of("running","cancelling").contains(analyzer.status().get("status"))&&System.nanoTime()<deadline)Thread.sleep(5);
        assertFalse(Set.of("running","cancelling").contains(analyzer.status().get("status")),"synthetic source cleanup failed: "+analyzer.status());
    }
    static class StrategyRepository extends RanchJobTest.MemoryRepository {
        private final List<String> recorded=new ArrayList<>();
        java.util.function.Consumer<String> onStatus=status->{};
        StrategyRepository() {
            state.putArray("library");
            var person=state.withArray("people").addObject().put("id","person-1").put("name","虚构甲")
                .put("notes","").put("goalType","friendship").put("goal","尊重边界地认识朋友");
            person.putArray("materials").addObject().put("id","P1").put("speaker","them").put("text","我今天想散步");
            person.putArray("strategies");person.putArray("analyses");
            person.putObject("profile").put("summary","这次表达了散步意愿").put("stale",false);
        }
        @Override public synchronized void writeJob(ObjectNode job) {
            super.writeJob(job);onStatus.accept(job.path("status").asText());recorded.add(job.path("phase").asText()+":"+job.path("step").asInt());
        }
        synchronized List<String> phases(){return List.copyOf(recorded);}
    }
}
