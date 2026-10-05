package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import static dev.ailiao.expert.ProductionDatabase.*;

final class ProductionJobQueue {
    record Claim(ObjectNode job,String owner,long epoch) {}
    private final ProductionDatabase db;
    ProductionJobQueue(ProductionDatabase db){this.db=db;}
    String enqueue(Connection c,String buildId,String kind,String target,ObjectNode input,String dedup) throws Exception {
        ObjectNode old=one(c,"SELECT * FROM ep_job WHERE dedup_key=?",dedup);
        if(old!=null){
            if(!old.path("build_id").asText().equals(buildId)||!old.path("kind").asText().equals(kind)||!old.path("input").equals(input)||!java.util.Objects.equals(old.path("target_revision_id").asText(null),target))throw new IllegalArgumentException("Job idempotency conflict");
            return old.path("id").asText();
        }
        String id=Json.id();execute(c,"INSERT INTO ep_job(id,build_id,kind,target_revision_id,expected_current_revision_id,input,dedup_key,created_by) VALUES(?,?,?,?,?,?,?,'system')",id,buildId,kind,target,target,input,dedup);return id;
    }
    Claim claim(String worker,Duration lease) throws Exception {
        long millis=positive(lease);
        return db.transaction(c->{
            var builds=query(c,"SELECT b.id FROM ep_build b WHERE EXISTS (SELECT 1 FROM ep_job j WHERE j.build_id=b.id AND NOT j.cancel_requested AND ((j.status='queued' AND j.next_run_at<=clock_timestamp()) OR (j.status='running' AND j.lease_until<=clock_timestamp())) AND (b.status='active' OR j.kind='interpret_message')) ORDER BY b.updated_at,b.id FOR UPDATE OF b SKIP LOCKED LIMIT 32");
            for(var build:builds){
                String id=build.path("id").asText();
                var job=one(c,"""
                    SELECT j.* FROM ep_job j JOIN ep_build b ON b.id=j.build_id
                    WHERE j.build_id=? AND NOT j.cancel_requested
                      AND ((j.status='queued' AND j.next_run_at<=clock_timestamp()) OR (j.status='running' AND j.lease_until<=clock_timestamp()))
                      AND (b.status='active' OR j.kind='interpret_message')
                      AND NOT EXISTS(SELECT 1 FROM ep_job live WHERE live.build_id=j.build_id AND live.id<>j.id
                        AND live.status='running' AND live.lease_until>clock_timestamp()
                        AND (live.kind='interpret_message')=(j.kind='interpret_message'))
                      AND (j.kind<>'interpret_message' OR NOT EXISTS(SELECT 1 FROM ep_job earlier
                        WHERE earlier.build_id=j.build_id AND earlier.kind='interpret_message' AND earlier.status IN ('queued','running')
                        AND (earlier.created_at,earlier.id)<(j.created_at,j.id)))
                    ORDER BY CASE WHEN j.kind='interpret_message' THEN 0 ELSE 1 END,j.created_at,j.id
                    FOR UPDATE OF j SKIP LOCKED LIMIT 1
                    """,id);
                if(job==null)continue;
                String jobId=job.path("id").asText();
                ObjectNode updated=one(c,"UPDATE ep_job SET status='running',phase='working',lease_owner=?,lease_until=clock_timestamp()+(? * interval '1 millisecond'),lease_epoch=lease_epoch+1,attempt=attempt+1,updated_at=now() WHERE id=? RETURNING *",worker,millis,jobId);
                return new Claim(updated,worker,updated.path("lease_epoch").asLong());
            }return null;
        });
    }
    boolean renew(Claim claim,Duration lease) throws Exception {
        long millis=positive(lease);
        return db.locked(build(claim),c->{if(valid(c,claim,true)==null)return false;
            return execute(c,"UPDATE ep_job SET lease_until=clock_timestamp()+(? * interval '1 millisecond'),updated_at=now() WHERE id=?",millis,id(claim))==1;});
    }
    boolean live(Claim claim) throws Exception {return db.read(c->valid(c,claim,false)!=null);}
    <T>T withLease(Claim claim,SqlWork<T> action) throws Exception {
        return db.locked(build(claim),c->{
            if(valid(c,claim,true)==null)throw new CancellationException("Job lease is no longer current");
            T value=action.run(c);
            ObjectNode after=one(c,"SELECT status FROM ep_job WHERE id=?",id(claim));
            if(after==null || ("running".equals(after.path("status").asText()) && valid(c,claim,true)==null))
                throw new CancellationException("Job lease expired before checkpoint commit");
            return value;
        });
    }
    void complete(Connection c,Claim claim,ObjectNode checkpoint) throws Exception {
        if(valid(c,claim,true)==null)throw new CancellationException("Job lease is no longer current");
        execute(c,"UPDATE ep_job SET status='succeeded',phase='done',checkpoint=?,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=?",checkpoint,id(claim));
    }
    void defer(Claim claim,String safeMessage,Duration delay) throws Exception {
        long millis=positive(delay);
        db.locked(build(claim),c->{if(valid(c,claim,true)==null)return null;
            execute(c,"UPDATE ep_job SET status='queued',phase='quota_wait',next_run_at=clock_timestamp()+(? * interval '1 millisecond'),error=?,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=?",millis,Json.object().put("message",safeMessage==null?"共享额度暂不可用":safeMessage.substring(0,Math.min(1000,safeMessage.length()))),id(claim));return null;});
    }
    void fail(Claim claim,String safeMessage) throws Exception {
        db.locked(build(claim),c->{if(valid(c,claim,true)==null)return null;
            execute(c,"UPDATE ep_job SET status='failed',phase='failed',error=?,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=?",Json.object().put("message",safeMessage==null?"任务失败":safeMessage.substring(0,Math.min(1000,safeMessage.length()))),id(claim));
            execute(c,"UPDATE ep_artifact_revision SET generation_status='failed',updated_at=now() WHERE id=? AND sealed_at IS NULL",claim.job().path("target_revision_id").asText(null));return null;});
    }
    void cancelGeneration(Connection c,String buildId) throws Exception {
        execute(c,"UPDATE ep_artifact_revision r SET generation_status='cancelled',updated_at=now() WHERE r.sealed_at IS NULL AND r.id IN (SELECT target_revision_id FROM ep_job WHERE build_id=? AND kind<>'interpret_message' AND status IN ('queued','running'))",buildId);
        execute(c,"UPDATE ep_job SET status='cancelled',cancel_requested=true,phase='cancelled',lease_epoch=lease_epoch+1,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE build_id=? AND kind<>'interpret_message' AND status IN ('queued','running')",buildId);
    }
    private ObjectNode valid(Connection c,Claim claim,boolean lock) throws Exception {
        return one(c,"""
            SELECT j.* FROM ep_job j JOIN ep_build b ON b.id=j.build_id
            WHERE j.id=? AND j.lease_owner=? AND j.lease_epoch=? AND j.status='running'
              AND j.lease_until>clock_timestamp() AND NOT j.cancel_requested
              AND (b.status='active' OR j.kind='interpret_message')
              AND (j.expected_current_revision_id IS NULL OR EXISTS(SELECT 1 FROM ep_artifact a JOIN ep_artifact_revision r ON r.artifact_id=a.id
                WHERE r.id=j.expected_current_revision_id AND a.current_revision_id=r.id))
            """+(lock?" FOR UPDATE OF j":""),id(claim),claim.owner(),claim.epoch());
    }
    private static String id(Claim c){return c.job().path("id").asText();}
    private static String build(Claim c){return c.job().path("build_id").asText();}
    private static long positive(Duration d){long m=d.toMillis();if(m<=0)throw new IllegalArgumentException("Positive lease required");return m;}
}
