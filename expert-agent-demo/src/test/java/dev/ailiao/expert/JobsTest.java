package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class JobsTest {
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
