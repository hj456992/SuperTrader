package dev.ailiao.expert;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EXPERT_DB_URL", matches=".+")
class ProductionDatabaseTest {
    ProductionDatabase db;
    String schema;
    @BeforeEach void setup() throws Exception {
        schema="ep_it_"+UUID.randomUUID().toString().replace("-", "");
        db=new ProductionDatabase(System.getenv("EXPERT_DB_URL"), System.getenv("EXPERT_DB_USER"), System.getenv("EXPERT_DB_PASSWORD"),schema);
        db.migrate();
    }
    @AfterEach void cleanup() throws Exception {
        if(db!=null) db.transaction(c->{ProductionDatabase.execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});
    }
    String build() throws Exception {
        String team=Json.id(),build=Json.id();
        db.transaction(c->{ProductionDatabase.execute(c,"INSERT INTO ep_team(id,name,created_by) VALUES(?,?,?)",team,"test team","test-admin");
            ProductionDatabase.execute(c,"INSERT INTO ep_build(id,team_id,build_no,created_by) VALUES(?,?,1,?)",build,team,"test-admin");return null;});
        return build;
    }
    @Test void migrationIsRepeatableAndTransactionsRollback() throws Exception {
        db.migrate();
        assertEquals(15,db.read(c->ProductionDatabase.one(c,"SELECT count(*) AS n FROM information_schema.tables WHERE table_schema=?",schema)).path("n").asInt());
        String id=Json.id();
        assertThrows(IllegalStateException.class,()->db.transaction(c->{
            ProductionDatabase.execute(c,"INSERT INTO ep_team(id,name,created_by) VALUES(?,?,?)",id,"must rollback","test-admin");
            throw new IllegalStateException("rollback marker");
        }));
        assertNull(db.read(c->ProductionDatabase.one(c,"SELECT id FROM ep_team WHERE id=?",id)));
    }
    @Test void currentArtifactCannotPointIntoAnotherBuild() throws Exception {
        String a=build(),b=build(),artifact=Json.id();
        db.transaction(c->{ProductionDatabase.execute(c,"INSERT INTO ep_artifact(id,build_id,kind,logical_key,created_by) VALUES(?,?,'book_summary','summary','test-admin')",artifact,b);return null;});
        assertThrows(SQLException.class,()->db.locked(a,c->{ProductionDatabase.execute(c,"UPDATE ep_build SET current_artifact_id=? WHERE id=?",artifact,a);return null;}));
        assertTrue(db.read(c->ProductionDatabase.one(c,"SELECT current_artifact_id FROM ep_build WHERE id=?",a)).path("current_artifact_id").isNull());
    }
    @Test void twoWorkersCannotClaimTheSameLiveJob() throws Exception {
        String b=build();var q=new ProductionJobQueue(db);
        String id=db.locked(b,c->q.enqueue(c,b,"prelearn",null,Json.object(),"test:"+b));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return q.claim("worker-a",Duration.ofSeconds(30));});
            var two=pool.submit(()->{start.await();return q.claim("worker-b",Duration.ofSeconds(30));});start.countDown();
            List<ProductionJobQueue.Claim> claims=new ArrayList<>();var x=one.get(10,TimeUnit.SECONDS);var y=two.get(10,TimeUnit.SECONDS);
            if(x!=null)claims.add(x);if(y!=null)claims.add(y);
            assertEquals(1,claims.size());assertEquals(id,claims.get(0).job().path("id").asText());
        } finally {pool.shutdownNow();}
    }
    @Test void expiredWorkerCannotRenewOrCommitAfterTakeover() throws Exception {
        String b=build();var q=new ProductionJobQueue(db);
        db.locked(b,c->q.enqueue(c,b,"prelearn",null,Json.object(),"test:"+b));
        var old=q.claim("old-worker",Duration.ofSeconds(30));assertNotNull(old);
        db.locked(b,c->{ProductionDatabase.execute(c,"UPDATE ep_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.job().path("id").asText());return null;});
        var next=q.claim("new-worker",Duration.ofSeconds(30));assertNotNull(next);assertTrue(next.epoch()>old.epoch());
        assertFalse(q.renew(old,Duration.ofSeconds(30)));
        assertThrows(CancellationException.class,()->q.withLease(old,c->{ProductionDatabase.execute(c,"UPDATE ep_build SET phase='summary' WHERE id=?",b);return null;}));
        assertEquals("prelearning",db.read(c->ProductionDatabase.one(c,"SELECT phase FROM ep_build WHERE id=?",b)).path("phase").asText());
        q.withLease(next,c->{q.complete(c,next,Json.object().put("step","done"));return null;});
        assertEquals("succeeded",db.read(c->ProductionDatabase.one(c,"SELECT status FROM ep_job WHERE id=?",next.job().path("id").asText())).path("status").asText());
    }
    @Test void pauseCancelsGenerationButStillAllowsControlMessage() throws Exception {
        String b=build();var q=new ProductionJobQueue(db);
        db.locked(b,c->q.enqueue(c,b,"prelearn",null,Json.object(),"test:g:"+b));
        var running=q.claim("generator",Duration.ofSeconds(30));assertNotNull(running);
        db.locked(b,c->{ProductionDatabase.execute(c,"UPDATE ep_build SET status='paused' WHERE id=?",b);q.cancelGeneration(c,b);q.enqueue(c,b,"interpret_message",null,Json.object(),"test:m:"+b);return null;});
        assertFalse(q.live(running));var control=q.claim("control",Duration.ofSeconds(30));assertNotNull(control);
        assertEquals("interpret_message",control.job().path("kind").asText());
        assertFalse(q.renew(running,Duration.ofSeconds(30)));
    }
    @Test void quotaDeferralPreservesPendingWorkAndOldLeaseCannotWrite() throws Exception {
        String b=build();var q=new ProductionJobQueue(db);
        String id=db.locked(b,c->q.enqueue(c,b,"prelearn",null,Json.object(),"test:quota:"+b));
        var claim=q.claim("quota-worker",Duration.ofSeconds(30));
        q.defer(claim,"共享额度暂不可用",Duration.ofSeconds(30));
        var job=db.read(c->ProductionDatabase.one(c,"SELECT * FROM ep_job WHERE id=?",id));
        assertEquals("queued",job.path("status").asText());assertTrue(job.path("lease_owner").isNull());
        assertFalse(q.live(claim));assertNull(q.claim("next",Duration.ofSeconds(30)));
        assertThrows(CancellationException.class,()->q.withLease(claim,c->{q.complete(c,claim,Json.object());return null;}));
    }

    @Test void leaseExpiryDuringCheckpointRollsBackWork() throws Exception {
        String b=build();var q=new ProductionJobQueue(db);
        db.locked(b,c->q.enqueue(c,b,"prelearn",null,Json.object(),"test:expiry:"+b));
        var claim=q.claim("worker",Duration.ofSeconds(30));
        assertThrows(CancellationException.class,()->q.withLease(claim,c->{
            ProductionDatabase.execute(c,"UPDATE ep_build SET phase='summary' WHERE id=?",b);
            ProductionDatabase.execute(c,"UPDATE ep_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",claim.job().path("id").asText());
            return null;
        }));
        assertEquals("prelearning",db.read(c->ProductionDatabase.one(c,"SELECT phase FROM ep_build WHERE id=?",b)).path("phase").asText());
    }

}
