package dev.garden;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.garden.ProfileAgentAcceptanceStrategyTest.*;
import static dev.garden.ProfileAgentAcceptanceStrategyLimitsTest.*;

/** Controlled barriers assert commit priority without timing guesses or a database. */
@Timeout(value=12,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class ProfileAgentAcceptanceStrategySaveTest {
    @Test void secondAttemptCancellationReleasesSourceAndRejectsLateValidAnswer()throws Exception {
        var repo=new Repo();var before=repo.read();var calls=new AtomicInteger();var source=new AtomicReference<FluxSink<Map<String,Object>>>();
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->calls.incrementAndGet()==1?ProfileRuntimeTest.answer(strategy("qa-invalid")):silent(source,entered,cancelled))));
        try(var a=analyzer(models,repo,Duration.ofSeconds(5))) {
            try {
                String run=a.start(repo.read(),TARGET,"strategy","第二次取消").get("id").toString();assertTrue(entered.await(1,TimeUnit.SECONDS));
                a.cancel("qa-obsolete");assertEquals("running",a.status().get("status"));a.cancel(run);a.cancel(run);
                assertTrue(cancelled.await(2,TimeUnit.SECONDS));stopped(a);emitAnswer(source.get(),strategy("M-lan-1"));
                assertEquals("cancelled",a.status().get("status"));assertEquals(2,calls.get());assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }finally{cleanup(a,source);}
        }
    }

    @Test void cancellationHoldingSaveMonitorRejectsAnAlreadyCompletedResponse()throws Exception {
        var repo=new Repo();var before=repo.read();var source=new AtomicReference<FluxSink<Map<String,Object>>>();var entered=new CountDownLatch(1);
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->silent(source,entered,new CountDownLatch(1)))));
        try(var a=analyzer(models,repo,Duration.ofSeconds(5))) {
            try {
                String run=a.start(repo.read(),TARGET,"strategy","保存前取消").get("id").toString();assertTrue(entered.await(1,TimeUnit.SECONDS));
                synchronized(a){emitAnswer(source.get(),strategy("M-lan-1"));a.cancel(run);}
                stopped(a);assertEquals("cancelled",a.status().get("status"));assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }finally{cleanup(a,source);}
        }
    }

    @Test void saveAlreadyHoldingMonitorWinsConcurrentCancellationExactlyOnce()throws Exception {
        var repo=new Repo();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);repo.saveEntered=entered;repo.saveRelease=release;
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->answer())));var thread=Executors.newSingleThreadExecutor();
        try(var a=analyzer(models,repo,Duration.ofSeconds(5))) {
            try {
                String run=a.start(repo.read(),TARGET,"strategy","保存优先").get("id").toString();assertTrue(entered.await(1,TimeUnit.SECONDS));
                var cancelStarted=new CountDownLatch(1);var cancellation=thread.submit(()->{cancelStarted.countDown();a.cancel(run);});assertTrue(cancelStarted.await(1,TimeUnit.SECONDS));
                release.countDown();cancellation.get(2,TimeUnit.SECONDS);stopped(a);
                assertEquals("done",a.status().get("status"));assertEquals(1,repo.saves);assertEquals(8,repo.read().path("revision").asInt());
                assertEquals(2,RanchData.target(repo.read(),TARGET).path("strategies").size());
            }finally{release.countDown();a.cancel();stopped(a);thread.shutdownNow();}
        }
    }

    @Test void businessChangeWhileProviderRunsRejectsStaleResultWithoutAnotherSave()throws Exception {
        var repo=new Repo();var source=new AtomicReference<FluxSink<Map<String,Object>>>();var entered=new CountDownLatch(1);
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->silent(source,entered,new CountDownLatch(1)))));
        try(var a=analyzer(models,repo,Duration.ofSeconds(5))) {
            try {
                a.start(repo.read(),TARGET,"strategy","修订竞争");assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(7,repo.read().path("revision").asInt());
                repo.update(7,s->s.withObject("self").put("about","新的虚构资料"));var edited=repo.read();emitAnswer(source.get(),strategy("M-lan-1"));stopped(a);
                assertEquals("error",a.status().get("status"));assertEquals(edited,repo.read());assertEquals(1,repo.saves);
            }finally{cleanup(a,source);}
        }
    }

    @Test void expiredBudgetAtMutationBoundaryCannotCommitTheValidatedResult()throws Exception {
        var repo=new Repo();var before=repo.read();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);repo.saveEntered=entered;repo.saveRelease=release;
        var models=registry((p,m,s)->CompletableFuture.completedFuture(adapter(request->answer())));
        try(var a=analyzer(models,repo,Duration.ofMillis(450))) {
            try {
                a.start(repo.read(),TARGET,"strategy","提交前到期");assertTrue(entered.await(1,TimeUnit.SECONDS));
                consumeMillis(600);release.countDown();stopped(a);
                assertEquals("error",a.status().get("status"));assertEquals(before,repo.read());assertEquals(0,repo.saves);
            }finally{release.countDown();a.cancel();stopped(a);}
        }
    }
}
