package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class JobsTest {
    @Test void closeReportsWorkerThatCannotStopWithinDeadline()throws Exception{
        Jobs jobs=new Jobs();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        try{
            jobs.start("import","ocr",job->{
                entered.countDown();
                try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return Json.object();}
                finally{finished.countDown();}
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,jobs::close,"close must report a still-running worker instead of pretending cleanup finished");
            assertEquals(1,finished.getCount());
        }finally{release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));jobs.close();}
    }
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void closeWaitsForWorkerCleanupAndPreservesCallerInterruption(boolean interrupted)throws Exception{
        Jobs jobs=new Jobs();ExecutorService closer=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),cleanup=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicBoolean cleanupCompleted=new AtomicBoolean();
        try{
            jobs.start("import","ocr",job->{
                entered.countDown();
                try{new CountDownLatch(1).await();return Json.object();}
                finally{
                    cleanup.countDown();
                    while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
                    // Real cleanup can need the Jobs monitor; close must not hold it while joining.
                    jobs.get(job.id);cleanupCompleted.set(true);finished.countDown();
                }
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<Boolean> closed=closer.submit(()->{if(interrupted)Thread.currentThread().interrupt();jobs.close();return Thread.currentThread().isInterrupted();});
            assertTrue(cleanup.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->closed.get(100,TimeUnit.MILLISECONDS),"close must not return while cancelled work is still cleaning up");
            release.countDown();assertEquals(interrupted,closed.get(2,TimeUnit.SECONDS));
            assertTrue(cleanupCompleted.get());
            assertThrows(IllegalStateException.class,()->jobs.start("import","ocr",job->Json.object()),"closed executor must reject work before registering a running job");
            assertEquals(1,jobs.snapshots().size());
        }finally{
            release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));jobs.close();closer.shutdownNow();
        }
    }
    @Test void cancelledWorkCannotCommitALateResult() throws Exception {
        try (Jobs jobs=new Jobs()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var wrote=new AtomicBoolean();
            var job=jobs.start("chat","",current->{
                entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.interrupted();}
                try{return current.commit(()->{wrote.set(true);return Json.object();});}finally{finished.countDown();}
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));job.cancel();release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));
            assertFalse(wrote.get());assertEquals("cancelled",job.snapshot().path("status").asText());
        }
    }
    @Test void committedResultCannotBeChangedByLateCancellation() throws Exception {
        var job=new Jobs.Job("chat","");job.commit(()->Json.object().put("value","saved"));job.cancel();
        assertEquals("completed",job.snapshot().path("status").asText());assertEquals("saved",job.snapshot().path("result").path("value").asText());
    }
}
