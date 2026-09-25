package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import dev.dsh.contract.llm.ModelRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value=10,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class StrategyRuntimeTest {
    @Test void twoAttemptsShareOneDeadlineAndSecondSilentSourceIsCancelled() throws Exception {
        var released=new CountDownLatch(1);var second=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var h=new Harness(request->{
            if(calls.incrementAndGet()==1)return StrategyExecutionTest.answer("unread-id").delaySubscription(Duration.ofMillis(1200));
            return Flux.create(sink->{sink.onCancel(released::countDown);second.countDown();});
        },Duration.ofSeconds(2))) {
            long start=System.nanoTime();
            assertThrows(TimeoutException.class,h::generate);
            assertTrue(second.await(1,TimeUnit.SECONDS));assertTrue(released.await(1,TimeUnit.SECONDS));
            assertEquals(2,calls.get());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3),"repair reset the total deadline");
        }
    }
    @Test void continuousChunksDoNotResetTheTotalDeadline() throws Exception {
        var released=new CountDownLatch(1);var scheduler=Executors.newSingleThreadScheduledExecutor();
        try(var h=new Harness(request->Flux.create(sink->{
            sink.onCancel(released::countDown);
            var ticking=scheduler.scheduleAtFixedRate(()->sink.next(Map.of("type","usage")),0,20,TimeUnit.MILLISECONDS);
            sink.onDispose(()->ticking.cancel(false));
        }),Duration.ofMillis(300))) {
            assertThrows(TimeoutException.class,h::generate);
            assertTrue(released.await(1,TimeUnit.SECONDS));assertEquals(1,h.calls.get());
        } finally {scheduler.shutdownNow();}
    }
    @Test void outputCapsCancelAtTheSourceWithoutWaitingForFinishOrRepair() throws Exception {
        for(boolean characters:List.of(true,false)) {
            var released=new CountDownLatch(1);
            try(var h=new Harness(request->Flux.create(sink->{
                sink.onCancel(released::countDown);
                if(characters)sink.next(Map.of("type","text-delta","text","字".repeat(50001)));
                else for(int i=0;i<20001&&!sink.isCancelled();i++)sink.next(Map.of("type","usage"));
                // Deliberately neither complete nor finish: the consumer must release the source.
            }),Duration.ofSeconds(2))) {
                assertThrows(StrategyRuntime.OutputLimitExceeded.class,h::generate);
                assertTrue(released.await(1,TimeUnit.SECONDS));assertEquals(1,h.calls.get());
            }
        }
    }
    @Test void exactCharacterAndEventLimitsRemainUsableWithoutDoubleAppendingBlockEnd() throws Exception {
        String json=validJson();String padded=json+" ".repeat(50000-json.length());
        try(var h=new Harness(request->Flux.concat(
            Flux.range(0,19996).map(i->Map.<String,Object>of("type","usage")),
            ProfileRuntimeTest.answer(padded)),Duration.ofSeconds(3))) {
            var result=h.generate();assertEquals("P1",result.path("evidenceIds").get(0).asText());assertEquals(1,h.calls.get());
        }
    }
    @Test void protocolFailuresNeverTriggerStructureRepair() throws Exception {
        for(String reason:List.of("missing","length","error","aborted","transport")) {
            try(var h=new Harness(request->{
                if(reason.equals("transport"))return Flux.error(new IllegalStateException("synthetic transport"));
                var text=Flux.just(Map.<String,Object>of("type","text-delta","text",validJson()));
                return reason.equals("missing")?text:text.concatWith(Flux.just(Map.of("type","finish","reason",Map.of("kind",reason))));
            },Duration.ofSeconds(2))) {
                assertThrows(IllegalStateException.class,h::generate,reason);assertEquals(1,h.calls.get(),reason);
            }
        }
    }
    @Test void malformedJsonGetsOnlyOneRepairAndTheSecondFailureStops() throws Exception {
        for(boolean fix:List.of(true,false)) {
            var calls=new AtomicInteger();
            try(var h=new Harness(request->calls.incrementAndGet()==2&&fix?StrategyExecutionTest.answer("P1"):ProfileRuntimeTest.answer("{"),Duration.ofSeconds(2))) {
                if(fix)assertEquals("P1",h.generate().path("evidenceIds").get(0).asText());
                else assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,h::generate);
                assertEquals(2,calls.get());
            }
        }
    }
    @Test void prepareTimeoutEndsLocalWaitAndNeverDispatchesLateCompletion() throws Exception {
        var future=new CompletableFuture<ModelRegistry.AdapterCall>();var subscriptions=new AtomicInteger();
        var models=StrategyExecutionTest.registry((provider,model,signal)->future);
        try(var run=new StrategyRuntime(models,Duration.ofMillis(200)).newRun(()->false)) {
            run.begin();
            assertThrows(TimeoutException.class,()->run.generate(input(),Store.JSON.createArrayNode(),(p,s,m)->{},()->{}));
            assertFalse(future.isDone());
            future.complete(StrategyExecutionTest.adapter(request->{subscriptions.incrementAndGet();return StrategyExecutionTest.answer("P1");}));
            assertEquals(0,subscriptions.get());
        }
    }
    @Test void progressReflectsBothAttemptsAndValidationInOrder() throws Exception {
        var events=new ArrayList<String>();var calls=new AtomicInteger();
        try(var h=new Harness(request->{events.add("source:"+(calls.incrementAndGet()));return StrategyExecutionTest.answer(calls.get()==1?"unread-id":"P1");},Duration.ofSeconds(2))) {
            h.run.generate(h.input,Store.JSON.createArrayNode(),(phase,step,message)->events.add(phase+":"+step),()->{});
            assertEquals(List.of("preparing:1","reasoning:1","source:1","validating:1","preparing:2","reasoning:2","source:2","validating:2"),events);
        }
    }
    @Test void revisionFailureDoesNotBecomeARepairOrModelCall() throws Exception {
        try(var h=new Harness(request->StrategyExecutionTest.answer("P1"),Duration.ofSeconds(2))) {
            assertThrows(IllegalStateException.class,()->h.run.generate(h.input,Store.JSON.createArrayNode(),(p,s,m)->{},()->{throw new IllegalStateException("stale");}));
            assertEquals(0,h.calls.get());
        }
    }
    static ObjectNode input(){
        var repo=new StrategyExecutionTest.StrategyRepository();var state=repo.read();
        var input=RanchData.input(state,"person-1");input.set("self",RanchData.input(state,"self"));
        input.set("profile",state.path("people").get(0).path("profile"));input.put("situation","询问是否想散步");input.putArray("knowledge");return input;
    }
    static String validJson(){
        return "{\"overview\":\"本次想散步\",\"steps\":[{\"title\":\"询问\",\"detail\":\"尊重选择\"}],\"reply\":\"一起散步吗？\",\"why\":\"尚不了解长期偏好\",\"evidenceIds\":[\"P1\"],\"knowledgeIds\":[]}";
    }
    static final class Harness implements AutoCloseable {
        final AtomicInteger calls=new AtomicInteger();final ObjectNode input=input();final StrategyRuntime.Run run;
        Harness(Function<Map<String,Object>,Flux<Map<String,Object>>> source,Duration timeout){
            var models=StrategyExecutionTest.registry((provider,model,signal)->CompletableFuture.completedFuture(StrategyExecutionTest.adapter(request->{
                calls.incrementAndGet();assertTrue(Store.JSON.valueToTree(request.get("maxTokens")).isIntegralNumber());
                assertEquals(6000,((Number)request.get("maxTokens")).intValue());return source.apply(request);
            })));
            run=new StrategyRuntime(models,timeout).newRun(()->false);run.begin();
        }
        ObjectNode generate() throws Exception{return run.generate(input,Store.JSON.createArrayNode(),(phase,step,message)->{},()->{});}
        public void close(){run.close();}
    }
}
