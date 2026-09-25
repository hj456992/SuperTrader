package dev.garden;

import dev.dsh.contract.llm.ModelRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.garden.ProfileAgentAcceptanceStrategyTest.*;

/** Independent whole-run budgets; time is intentionally consumed only in deadline tests. */
@Timeout(value=12,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class ProfileAgentAcceptanceStrategyLimitsTest {
    static RanchAnalyzer analyzer(ModelRegistry models,Repo repo,Duration budget){return new RanchAnalyzer(models,repo,new RanchKnowledge(repo),null,new StrategyRuntime(models,budget));}
    static Flux<Map<String,Object>> silent(AtomicReference<FluxSink<Map<String,Object>>> reference,CountDownLatch entered,CountDownLatch cancelled){return Flux.create(sink->{sink.onCancel(cancelled::countDown);reference.set(sink);entered.countDown();});}
    static void consumeMillis(long millis)throws Exception {assertFalse(new CountDownLatch(1).await(millis,TimeUnit.MILLISECONDS));}
    static void emitAnswer(FluxSink<Map<String,Object>> sink,String text){ProfileRuntimeTest.answer(text).subscribe(sink::next,sink::error,sink::complete);}

    @Test void citationRepairOnlyGetsTheRemainingWholeRunBudget()throws Exception {
        var repo=new Repo();var before=repo.read();var calls=new AtomicInteger();
        var first=new AtomicReference<FluxSink<Map<String,Object>>>();var second=new AtomicReference<FluxSink<Map<String,Object>>>();
        var firstEntered=new CountDownLatch(1);var secondEntered=new CountDownLatch(1);var secondCancelled=new CountDownLatch(1);
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->calls.incrementAndGet()==1?silent(first,firstEntered,new CountDownLatch(1)):silent(second,secondEntered,secondCancelled))));
        try(var a=analyzer(models,repo,Duration.ofSeconds(2))) {
            try {
                long start=System.nanoTime();a.start(repo.read(),TARGET,"strategy","共享预算");assertTrue(firstEntered.await(1,TimeUnit.SECONDS));
                consumeMillis(1200);emitAnswer(first.get(),strategy("qa-invalid"));assertTrue(secondEntered.await(1,TimeUnit.SECONDS));
                assertTrue(secondCancelled.await(1500,TimeUnit.MILLISECONDS),"second stream must lose first attempt's elapsed budget");stopped(a);
                assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2900,"two attempts must not reset the two-second deadline");
                assertEquals("error",a.status().get("status"));assertEquals(2,calls.get());assertEquals(0,repo.saves);assertEquals(before,repo.read());
            }finally{a.cancel();if(first.get()!=null)first.get().complete();if(second.get()!=null)second.get().complete();stopped(a);}
        }
    }

    @Test void repeatedSmallChunksDoNotRenewTheDeadline()throws Exception {
        var repo=new Repo();var before=repo.read();var source=new AtomicReference<FluxSink<Map<String,Object>>>();
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var calls=new AtomicInteger();
        var ticker=Executors.newSingleThreadScheduledExecutor();
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{calls.incrementAndGet();return silent(source,entered,cancelled);})));
        try(var a=analyzer(models,repo,Duration.ofMillis(600))) {
            try {
                a.start(repo.read(),TARGET,"strategy","持续片段");assertTrue(entered.await(1,TimeUnit.SECONDS));
                ticker.scheduleAtFixedRate(()->source.get().next(Map.of("type","text-delta","index",0,"text"," ")),0,30,TimeUnit.MILLISECONDS);
                assertTrue(cancelled.await(1400,TimeUnit.MILLISECONDS),"activity cannot renew total budget");stopped(a);
                assertEquals("error",a.status().get("status"));assertEquals(1,calls.get());assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }finally{ticker.shutdownNow();cleanup(a,source);}
        }
    }

    @Test void retrievalExhaustionOrCancellationCannotBeginPreparation()throws Exception {
        for(boolean cancel:List.of(false,true)) {
            var repo=new Repo();repo.state.set("library",ProfileAgentAcceptanceTest.state().path("library"));var before=repo.read();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);repo.bookEntered=entered;repo.bookRelease=release;
            var preparations=new AtomicInteger();var models=registry((p,m,s)->{preparations.incrementAndGet();return CompletableFuture.completedFuture(adapter(request->answer()));});
            try(var a=analyzer(models,repo,Duration.ofMillis(450))) {
                try {
                    String run=a.start(repo.read(),TARGET,"strategy","倾听").get("id").toString();assertTrue(entered.await(1,TimeUnit.SECONDS));
                    if(cancel)a.cancel(run);else consumeMillis(600);
                    release.countDown();stopped(a);assertEquals(cancel?"cancelled":"error",a.status().get("status"));
                    assertEquals(0,preparations.get());assertEquals(before,repo.read());assertEquals(0,repo.saves);
                }finally{release.countDown();a.cancel();stopped(a);}
            }
        }
    }

    @Test void preparationDeadlineEndsLocallyAndItsLateResultNeverSubscribes()throws Exception {
        var repo=new Repo();var before=repo.read();var entered=new CountDownLatch(1);
        var future=new CompletableFuture<ModelRegistry.AdapterCall>();var subscriptions=new AtomicInteger();
        var models=registry((p,m,s)->{entered.countDown();return future;});
        try(var a=analyzer(models,repo,Duration.ofMillis(350))) {
            try {
                a.start(repo.read(),TARGET,"strategy","准备超时");assertTrue(entered.await(1,TimeUnit.SECONDS));stopped(a);
                assertEquals("error",a.status().get("status"));assertFalse(future.isDone());
                assertTrue(future.complete(adapter(request->Flux.defer(()->{subscriptions.incrementAndGet();return answer();}))));
                assertEquals(0,subscriptions.get());assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }finally{a.cancel();future.complete(adapter(request->Flux.empty()));stopped(a);}
        }
    }

    @Test void characterAndEventOverflowCancelOriginalSourceWithoutFinishOrRepair()throws Exception {
        for(boolean characters:List.of(true,false)) {
            var repo=new Repo();var before=repo.read();var source=new AtomicReference<FluxSink<Map<String,Object>>>();
            var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var calls=new AtomicInteger();
            var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{calls.incrementAndGet();return silent(source,entered,cancelled);})));
            try(var a=analyzer(models,repo,Duration.ofSeconds(8))) {
                try {
                    a.start(repo.read(),TARGET,"strategy","有界输出");assertTrue(entered.await(1,TimeUnit.SECONDS));
                    if(characters)source.get().next(Map.of("type","text-delta","index",0,"text","字".repeat(50001)));
                    else for(int i=0;i<20001;i++)source.get().next(Map.of("type","usage","inputTokens",0,"outputTokens",0));
                    assertTrue(cancelled.await(2,TimeUnit.SECONDS),"overflow must cancel without waiting for finish or deadline");stopped(a);
                    assertEquals("error",a.status().get("status"));assertEquals(1,calls.get());assertEquals(before,repo.read());assertEquals(0,repo.saves);
                }finally{cleanup(a,source);}
            }
        }
    }

    @Test void transmissionAndNonStopResultsNeverTriggerStructureRepair()throws Exception {
        for(String mode:List.of("missing-finish","length","aborted","error","transport","invalid-json")) {
            var repo=new Repo();var before=repo.read();var calls=new AtomicInteger();
            var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{
                calls.incrementAndGet();
                if(mode.equals("transport"))return Flux.error(new IllegalArgumentException("synthetic adapter failure is not invalid model JSON"));
                if(mode.equals("invalid-json"))return ProfileRuntimeTest.answer("{invalid}");
                var delta=Map.<String,Object>of("type","text-delta","index",0,"text",strategy("M-lan-1"));
                if(mode.equals("missing-finish"))return Flux.just(delta);
                return Flux.just(delta,Map.of("type","finish","reason",Map.of("kind",mode)));
            })));
            try(var a=analyzer(models,repo,Duration.ofSeconds(3))) {
                a.start(repo.read(),TARGET,"strategy","协议边界");stopped(a);assertEquals("error",a.status().get("status"),mode);
                assertEquals(mode.equals("invalid-json")?2:1,calls.get(),mode);assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }
        }
    }

    @Test void exactLimitsRemainUsableAndBlockEndDoesNotDuplicateText()throws Exception {
        for(boolean characters:List.of(true,false)) {
            var repo=new Repo();var calls=new AtomicInteger();
            var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->{
                calls.incrementAndGet();String text=strategy("M-lan-1");
                if(characters)return ProfileRuntimeTest.answer(text+" ".repeat(50000-text.length()));
                var noise=Flux.range(0,19996).map(i->Map.<String,Object>of("type","usage","inputTokens",0,"outputTokens",0));
                return Flux.concat(noise,answer()); // The existing answer helper emits exactly four events.
            })));
            try(var a=analyzer(models,repo,Duration.ofSeconds(5))) {
                a.start(repo.read(),TARGET,"strategy","精确上限");stopped(a);
                assertEquals("done",a.status().get("status"),characters?"50000 characters":"20000 events");assertEquals(1,calls.get());assertEquals(1,repo.saves);
            }
        }
    }
}
