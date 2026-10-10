package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.ailiao.expert.ProductionDatabase.*;

/** PostgreSQL权威的管理员构建服务。所有模型调用都在短事务外；提交受构建锁和任务租约约束。 */
final class ProductionService implements AutoCloseable {
    private static final String ACTOR="local-admin";
    private final ProductionDatabase db;
    private final ProductionJobQueue queue;
    private final ProductionModel model;
    private final LabStore legacy;
    private final ProductionRedis redis;
    private final Path sharedFiles;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService renewals=Executors.newScheduledThreadPool(2);
    private final AtomicBoolean started=new AtomicBoolean();
    private volatile boolean closed;
    ProductionService(ProductionDatabase db,ProductionModel model,LabStore legacy,ProductionRedis redis,Path sharedFiles){
        this.db=db;this.queue=new ProductionJobQueue(db);this.model=model;this.legacy=legacy;this.redis=redis;this.sharedFiles=sharedFiles.toAbsolutePath().normalize();
    }
    void start(){if(started.compareAndSet(false,true))for(int i=0;i<2;i++)workers.submit(this::workLoop);}
    @Override public void close(){closed=true;workers.shutdownNow();renewals.shutdownNow();try{workers.awaitTermination(5,TimeUnit.SECONDS);renewals.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static String s(JsonNode n,String field){return n.path(field).asText("");}
    private static String nullable(JsonNode n,String field){String v=s(n,field);return v.isBlank()?null:v;}
    private static void uuid(String id){try{UUID.fromString(id);}catch(Exception e){throw error(400,"INVALID_ID","身份格式无效");}}
    private static ProductionException error(int code,String name,String message){return new ProductionException(code,name,message);}
    private ObjectNode build(Connection c,String id)throws Exception{ObjectNode b=one(c,"SELECT * FROM ep_build WHERE id=?",id);if(b==null)throw error(404,"NOT_FOUND","构建不存在");return b;}
    private static ArrayNode nodes(List<ObjectNode> rows){ArrayNode a=Json.array();rows.forEach(a::add);return a;}
    private ObjectNode artifact(Connection c,String id)throws Exception{return one(c,"SELECT a.*,r.body,r.system_prompt,r.content_sha256,r.generation_status,r.review_status,r.revision_no,r.change_reason FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.id=?",id);}
    private ObjectNode current(Connection c,String buildId)throws Exception{ObjectNode b=build(c,buildId);return nullable(b,"current_artifact_id")==null?null:artifact(c,s(b,"current_artifact_id"));}
    private ObjectNode creation(Connection c,String buildId)throws Exception{return (ObjectNode)one(c,"SELECT context_snapshot FROM ep_message WHERE build_id=? AND client_request_id='build:create'",buildId).path("context_snapshot").path("creationRequest");}

    /** Canonical source selection is the durable identity of one proposed team. */
    ObjectNode propose(ObjectNode proposal)throws Exception{
        ArrayNode supplied=ProductionRules.array(proposal,"documents",true);
        if(supplied.size()>8)throw error(422,"DOCUMENT_NOT_READY","请选择 1–8 份资料");
        List<ObjectNode> selected=new ArrayList<>();Set<String> documents=new HashSet<>();
        JsonNode library=legacy.state().path("documents");
        for(JsonNode item:supplied){
            String doc=Json.required(item,"documentId",80),version=Json.required(item,"documentVersionId",80);uuid(doc);uuid(version);
            if(!documents.add(doc))throw error(422,"DOCUMENT_NOT_READY","同一资料只能选择一个版本");
            JsonNode matched=null;for(JsonNode candidate:library)if(doc.equals(s(candidate,"id"))){matched=candidate;break;}
            if(matched==null)throw error(422,"DOCUMENT_NOT_READY","资料不存在");
            boolean ready=false;for(JsonNode candidate:matched.path("versions"))if(version.equals(s(candidate,"id"))&&candidate.path("chunkCount").asInt()>0){ready=true;break;}
            if(!ready)throw error(422,"DOCUMENT_NOT_READY","资料版本不存在、未就绪或不属于所选资料");
            selected.add(Json.object().put("documentId",doc).put("documentVersionId",version).put("title",s(matched,"title")));
        }
        selected.sort(Comparator.comparing((ObjectNode n)->s(n,"documentId")).thenComparing(n->s(n,"documentVersionId")));
        ArrayNode pairs=Json.array(),legacyPairs=Json.array();List<String> titles=new ArrayList<>();
        for(ObjectNode item:selected){pairs.add(Json.object().put("documentId",s(item,"documentId")).put("documentVersionId",s(item,"documentVersionId")));legacyPairs.add(Json.object().put("documentId",s(item,"documentId")).put("versionId",s(item,"documentVersionId")));titles.add(s(item,"title"));}
        try{legacy.selectedChunks(legacyPairs);}catch(IllegalArgumentException ex){throw error(422,"DOCUMENT_NOT_READY",ex.getMessage());}
        String identity="workbench:v1:"+ACTOR+":"+pairs;
        String team=UUID.nameUUIDFromBytes((identity+":team").getBytes(StandardCharsets.UTF_8)).toString();
        String build=UUID.nameUUIDFromBytes((identity+":build").getBytes(StandardCharsets.UTF_8)).toString();
        while(true){String candidate=build;ObjectNode found=db.read(c->one(c,"SELECT status FROM ep_build WHERE id=?",candidate));if(found==null||!"cancelled".equals(s(found,"status")))break;build=UUID.nameUUIDFromBytes((identity+":after:"+build).getBytes(StandardCharsets.UTF_8)).toString();}
        String name="资料专家团队："+String.join("、",titles);if(name.length()>200)name=name.substring(0,200);
        ObjectNode request=Json.object().put("origin","workbench").put("teamId",team).put("teamName",name).put("name",name)
            .put("responsibility","学习全部所选资料中的方法，按有依据的主题形成同一团队内的专业分工，并回答适用问题；资料不足时说明边界。");
        request.set("documents",pairs);
        return create(build,request);
    }

    ObjectNode create(String buildId,ObjectNode request)throws Exception{
        uuid(buildId);String team=Json.required(request,"teamId",80);uuid(team);Json.required(request,"name",200);Json.required(request,"responsibility",4000);
        String fingerprint=ProductionRules.hash(request);
        ObjectNode existing=db.read(c->one(c,"SELECT context_snapshot FROM ep_message WHERE build_id=? AND client_request_id='build:create'",buildId));
        if(existing!=null){if(!fingerprint.equals(existing.path("context_snapshot").path("requestHash").asText()))throw error(409,"IDEMPOTENCY_KEY_REUSED","构建身份已用于其他内容");return acceptedBuild(buildId);}
        ArrayNode selections=Json.array();for(JsonNode d:ProductionRules.array(request,"documents",true))selections.add(Json.object().put("documentId",s(d,"documentId")).put("versionId",s(d,"documentVersionId")));
        ArrayNode chunks;try{chunks=legacy.selectedChunks(selections);}catch(IllegalArgumentException ex){throw error(422,"DOCUMENT_NOT_READY",ex.getMessage());}
        ArrayNode versions=Json.array();Files.createDirectories(sharedFiles);
        for(JsonNode d:selections){String v=s(d,"versionId");uuid(v);ObjectNode version=legacy.documentVersion(v);version.put("documentId",s(d,"documentId"));
            if(version.path("parser").isMissingNode()||version.path("parser").isNull())version.set("parser",Json.object().put("provider","unknown").put("origin","legacy"));
            else if(!version.path("parser").isObject())throw error(422,"INVALID_PARSER_METADATA","资料解析元数据必须为对象");versions.add(version);
            Path source=legacy.original(v),target=sharedFiles.resolve(v+".pdf");byte[] bytes=Files.readAllBytes(source);
            if(!ProductionRules.hashBytes(bytes).equals(s(version,"sha256")))throw error(422,"SOURCE_MISMATCH","原文校验失败");
            if(!Files.exists(target)){Path tmp=Files.createTempFile(sharedFiles,"upload-",".tmp");try{Files.write(tmp,bytes);Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}}
            if(!ProductionRules.hashBytes(Files.readAllBytes(target)).equals(s(version,"sha256")))throw error(422,"SOURCE_MISMATCH","共享原文校验失败");
        }
        db.transaction(c->{
            execute(c,"INSERT INTO ep_team(id,name,created_by) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",team,request.path("teamName").asText(s(request,"name")),ACTOR);
            one(c,"SELECT id FROM ep_team WHERE id=? FOR UPDATE",team);
            ObjectNode dup=one(c,"SELECT context_snapshot FROM ep_message WHERE build_id=? AND client_request_id='build:create'",buildId);
            if(dup!=null){if(!fingerprint.equals(dup.path("context_snapshot").path("requestHash").asText()))throw error(409,"IDEMPOTENCY_KEY_REUSED","构建身份冲突");return null;}
            int no=one(c,"SELECT coalesce(max(build_no),0)+1 n FROM ep_build WHERE team_id=?",team).path("n").asInt();
            execute(c,"INSERT INTO ep_build(id,team_id,build_no,created_by) VALUES(?,?,?,?)",buildId,team,no,ACTOR);
            for(JsonNode version:versions){String v=s(version,"id"),doc=s(version,"documentId");String title="已选资料";for(JsonNode chunk:chunks)if(v.equals(s(chunk,"versionId"))){title=s(chunk,"title");break;}
                execute(c,"INSERT INTO ep_document(id,title,created_by) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",doc,title,ACTOR);
                execute(c,"INSERT INTO ep_document_version(id,document_id,version_no,original_object_key,original_sha256,ocr_object_key,parser_config,parse_status,page_count,created_by) VALUES(?,?,?,?,?,?,?,'succeeded',?,?) ON CONFLICT(id) DO NOTHING",v,doc,version.path("number").asInt(),v+".pdf",s(version,"sha256"),"pg:"+v,version.path("parser"),version.path("pageCount").asInt(),ACTOR);
                execute(c,"INSERT INTO ep_build_document(build_id,document_id,document_version_id,created_by) VALUES(?,?,?,?)",buildId,doc,v,ACTOR);
            }
            int chunkNo=0;for(JsonNode chunk:chunks){String id=UUID.nameUUIDFromBytes(s(chunk,"id").getBytes(StandardCharsets.UTF_8)).toString();String text=s(chunk,"text");execute(c,"INSERT INTO ep_source_chunk(id,document_version_id,page_no,chunk_no,text,text_sha256,locator,created_by) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",id,s(chunk,"versionId"),chunk.path("page").asInt(),chunkNo++,text,ProductionRules.hashBytes(text.getBytes(StandardCharsets.UTF_8)),Json.object().put("legacyId",s(chunk,"id")),ACTOR);}
            ObjectNode ctx=Json.object().put("requestHash",fingerprint);ctx.set("creationRequest",request.deepCopy());
            addMessage(c,buildId,"system","build:create","构建已保存，开始预学习",ctx,"succeeded",null);
            ArrayNode selected=sourceChunks(c,buildId);List<ArrayNode> batches=batches(selected);
            for(int i=0;i<batches.size();i++)newArtifact(c,buildId,"learning_unit",null,"learning:"+i,i,Json.object().put("batchNo",i).set("passages",batches.get(i)));
            String summary=newArtifact(c,buildId,"book_summary",null,"summary",100,Json.object());
            execute(c,"UPDATE ep_build SET current_artifact_id=? WHERE id=?",summary,buildId);
            queue.enqueue(c,buildId,"prelearn",null,Json.object(),"prelearn:"+buildId+":"+Json.id());event(c,buildId,"build.created",Json.object());return null;
        });return acceptedBuild(buildId);
    }
    private ObjectNode acceptedBuild(String id)throws Exception{return db.read(c->{ObjectNode b=build(c,id);return Json.object().put("buildId",id).put("status",s(b,"status")).put("phase",s(b,"phase")).put("httpStatus",202).put("lockVersion",s(b,"lock_version")).put("snapshotUrl","/api/expert-production/v1/builds/"+id+"/snapshot");});}
    private List<ArrayNode> batches(ArrayNode chunks){List<ArrayNode> out=new ArrayList<>();ArrayNode batch=Json.array();int chars=0;for(JsonNode chunk:chunks){int size=s(chunk,"text").length();if(chars+size>16000&&!batch.isEmpty()){out.add(batch);batch=Json.array();chars=0;}batch.add(chunk);chars+=size;}if(!batch.isEmpty())out.add(batch);return out;}
    private ArrayNode sourceChunks(Connection c,String id)throws Exception{return nodes(query(c,"SELECT c.id,c.document_version_id AS \"documentVersionId\",c.page_no AS \"pageNo\",c.text,c.chapter_path AS \"chapterPath\" FROM ep_source_chunk c JOIN ep_build_document d ON d.document_version_id=c.document_version_id WHERE d.build_id=? ORDER BY c.document_version_id,c.page_no,c.chunk_no",id));}
    private String newArtifact(Connection c,String b,String kind,String role,String key,int order,ObjectNode config)throws Exception{
        String a=Json.id(),r=Json.id();execute(c,"INSERT INTO ep_artifact(id,build_id,kind,agent_role,logical_key,sort_no,created_by) VALUES(?,?,?,?,?,?,?)",a,b,kind,role,key,order,ACTOR);
        execute(c,"INSERT INTO ep_artifact_revision(id,build_id,artifact_id,revision_no,generator_config,created_by) VALUES(?,?,?,1,?,?)",r,b,a,config,ACTOR);
        execute(c,"UPDATE ep_artifact SET current_revision_id=? WHERE id=?",r,a);return a;
    }
    private String addMessage(Connection c,String b,String role,String client,String content,ObjectNode context,String status,String reply)throws Exception{
        String id=Json.id();long seq=one(c,"UPDATE ep_build SET message_seq=message_seq+1,updated_at=now() WHERE id=? RETURNING message_seq",b).path("message_seq").asLong();
        execute(c,"INSERT INTO ep_message(id,build_id,seq,role,actor_id,client_request_id,content,context_snapshot,processing_status,reply_to_message_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,b,seq,role,ACTOR,client,content,context,status,reply,ACTOR);return id;
    }
    private void event(Connection c,String b,String type,ObjectNode payload)throws Exception{long seq=one(c,"UPDATE ep_build SET event_seq=event_seq+1,updated_at=now() WHERE id=? RETURNING event_seq",b).path("event_seq").asLong();execute(c,"INSERT INTO ep_event(id,build_id,seq,type,payload,created_by) VALUES(?,?,?,?,?,?)",Json.id(),b,seq,type,payload,ACTOR);}
    private void bump(Connection c,String b)throws Exception{execute(c,"UPDATE ep_build SET lock_version=lock_version+1,updated_at=now() WHERE id=?",b);}
    ObjectNode list()throws Exception{return db.read(c->Json.object().set("builds",nodes(query(c,"SELECT b.id AS \"buildId\",t.name,b.phase,b.status,b.updated_at AS \"updatedAt\" FROM ep_build b JOIN ep_team t ON t.id=b.team_id ORDER BY b.created_at DESC"))));}
    ObjectNode events(String b,long after,int limit)throws Exception{return db.read(c->{build(c,b);return Json.object().set("events",nodes(query(c,"SELECT seq::text,type,payload,created_at AS \"createdAt\" FROM ep_event WHERE build_id=? AND seq>? ORDER BY seq LIMIT ?",b,after,Math.max(1,Math.min(limit,200)))));});}
    ObjectNode snapshot(String b)throws Exception{return db.read(c->snapshot(c,b));}
    private ObjectNode snapshot(Connection c,String id)throws Exception{
        ObjectNode b=build(c,id);ObjectNode out=Json.object().put("buildId",id).put("phase",s(b,"phase")).put("status",s(b,"status")).put("lockVersion",s(b,"lock_version")).put("lastEventSeq",s(b,"event_seq")).put("currentArtifactId",s(b,"current_artifact_id")).put("focusRevisionId",s(b,"focus_revision_id")).put("waitingQuestionMessageId",s(b,"waiting_question_message_id")).put("nextStageImplemented",true);
        ArrayNode artifacts=Json.array();for(ObjectNode row:query(c,"SELECT * FROM ep_artifact WHERE build_id=? ORDER BY sort_no,id",id)){
            ObjectNode a=Json.object().put("id",s(row,"id")).put("kind",s(row,"kind")).put("agentRole",s(row,"agent_role")).put("logicalKey",s(row,"logical_key")).put("sortNo",row.path("sort_no").asInt()).put("dependencyState",s(row,"dependency_state"));ArrayNode revisions=Json.array();
            for(ObjectNode rev:query(c,"SELECT * FROM ep_artifact_revision WHERE artifact_id=? ORDER BY revision_no",s(row,"id"))){ObjectNode r=revision(c,rev);revisions.add(r);if(s(rev,"id").equals(s(row,"current_revision_id")))a.set("currentRevision",r);}
            a.set("revisions",revisions);artifacts.add(a);if(s(row,"id").equals(s(b,"current_artifact_id")))out.set("currentArtifact",a);if("book_summary".equals(s(row,"kind")))out.set("summary",a.path("currentRevision"));
        }out.set("artifacts",artifacts);if(!out.has("currentArtifact"))out.putNull("currentArtifact");
        out.set("jobs",nodes(query(c,"SELECT id,kind,status,phase,target_revision_id AS \"targetRevisionId\",checkpoint,error,attempt FROM ep_job WHERE build_id=? ORDER BY created_at",id)));
        ObjectNode request=creation(c,id);if("workbench".equals(s(request,"origin"))){ObjectNode proposal=Json.object();proposal.set("documents",request.path("documents").deepCopy());proposal.set("sources",nodes(query(c,"SELECT c.id AS \"chunkId\",c.document_version_id AS \"documentVersionId\",c.page_no AS \"pageNo\",d.title FROM ep_source_chunk c JOIN ep_build_document bd ON bd.document_version_id=c.document_version_id JOIN ep_document d ON d.id=bd.document_id WHERE bd.build_id=? ORDER BY c.document_version_id,c.page_no,c.chunk_no",id)));out.set("proposal",proposal);}
        out.set("clarifications",nodes(query(c,"SELECT id,artifact_id AS \"artifactId\",revision_id AS \"revisionId\",kind,question,status,answer,opened_message_id AS \"openedMessageId\" FROM ep_clarification WHERE build_id=? ORDER BY created_at",id)));
        out.set("reviews",nodes(query(c,"SELECT id,revision_id AS \"revisionId\",scope,decision,reason,actor_id AS \"actorId\",message_id AS \"messageId\",action_no AS \"actionNo\",presented_message_id AS \"presentedMessageId\",reviewed_sha256 AS \"reviewedSha256\",decided_at AS \"decidedAt\" FROM ep_review WHERE build_id=? ORDER BY decided_at,id",id)));
        ArrayNode messages=Json.array();for(ObjectNode m:query(c,"SELECT * FROM ep_message WHERE build_id=? ORDER BY seq",id)){ObjectNode view=Json.object().put("id",s(m,"id")).put("seq",s(m,"seq")).put("role",s(m,"role")).put("content",s(m,"content")).put("processingStatus",s(m,"processing_status"));view.set("result",m.path("result"));view.set("interpretation",m.path("interpretation"));if(m.path("context_snapshot").has("presentation"))view.set("presentation",m.path("context_snapshot").path("presentation"));messages.add(view);}out.set("messages",messages);
        out.set("allowedActions",Json.MAPPER.valueToTree(List.of("ask","read_source","approve","reject","pause","resume","cancel","retry")));return out;
    }
    private ObjectNode revision(Connection c,ObjectNode r)throws Exception{
        ObjectNode out=Json.object().put("id",s(r,"id")).put("revisionId",s(r,"id")).put("revisionNo",s(r,"revision_no")).put("systemPrompt",s(r,"system_prompt")).put("contentSha256",s(r,"content_sha256")).put("generationStatus",s(r,"generation_status")).put("reviewStatus",s(r,"review_status")).put("changeReason",s(r,"change_reason"));out.set("body",r.path("body"));
        out.set("sources",nodes(query(c,"SELECT s.chunk_id AS \"chunkId\",s.document_version_id AS \"documentVersionId\",c.page_no AS \"pageNo\",s.start_offset AS \"startOffset\",s.end_offset AS \"endOffset\",s.purpose FROM ep_revision_source s JOIN ep_source_chunk c ON c.id=s.chunk_id WHERE revision_id=? ORDER BY s.chunk_id,s.start_offset",s(r,"id"))));return out;
    }
    ObjectNode source(String b,String chunk)throws Exception{return source(b,chunk,null,null);}
    ObjectNode source(String b,String chunk,Integer start,Integer end)throws Exception{
        uuid(b);uuid(chunk);
        ObjectNode result=db.read(c->{ObjectNode row=one(c,"SELECT c.id,c.id AS \"chunkId\",c.text,c.page_no AS \"pageNo\",c.document_version_id AS \"documentVersionId\",c.text_sha256 AS \"textSha256\",c.chapter_path AS \"chapterPath\",c.quality_flags AS \"qualityFlags\",c.locator FROM ep_source_chunk c JOIN ep_build_document d ON d.document_version_id=c.document_version_id WHERE d.build_id=? AND c.id=?",b,chunk);if(row==null)throw error(404,"SOURCE_NOT_FOUND","原文不属于此构建");return row;});
        String text=s(result,"text");int length=text.codePointCount(0,text.length());int from=start==null?0:start,to=end==null?length:end;
        if(from<0||to<=from||to>length)throw error(422,"INVALID_SOURCE_RANGE","原文码点区间越界");
        result.put("text",text.substring(text.offsetByCodePoints(0,from),text.offsetByCodePoints(0,to))).put("startOffset",from).put("endOffset",to);
        result.put("originalPageUrl","/api/expert-production/v1/builds/"+b+"/source-versions/"+s(result,"documentVersionId")+"/pages/"+result.path("pageNo").asInt()+"#page="+result.path("pageNo").asInt());return result;
    }
    Path original(String b,String version)throws Exception{uuid(version);boolean found=db.read(c->one(c,"SELECT document_version_id FROM ep_build_document WHERE build_id=? AND document_version_id=?",b,version)!=null);if(!found)throw error(404,"SOURCE_NOT_FOUND","资料不属于此构建");return sharedFiles.resolve(version+".pdf");}
    Path original(String b,String version,int page)throws Exception{uuid(version);ObjectNode found=db.read(c->one(c,"SELECT v.page_count FROM ep_build_document d JOIN ep_document_version v ON v.id=d.document_version_id WHERE d.build_id=? AND d.document_version_id=?",b,version));if(found==null)throw error(404,"SOURCE_NOT_FOUND","资料不属于此构建");if(page<1||page>found.path("page_count").asInt())throw error(422,"INVALID_PAGE","原文页码越界");return sharedFiles.resolve(version+".pdf");}

    ObjectNode message(String b,ObjectNode request)throws Exception{
        uuid(b);String client=Json.required(request,"clientRequestId",150);Json.required(request,"content",12000);String hash=ProductionRules.hash(request);
        return db.locked(b,c->{
            ObjectNode old=one(c,"SELECT * FROM ep_message WHERE build_id=? AND client_request_id=?",b,client);if(old!=null){if(!hash.equals(old.path("context_snapshot").path("requestHash").asText()))throw error(409,"IDEMPOTENCY_KEY_REUSED","消息身份已用于其他内容");return messageResponse(old);}
            ObjectNode build=build(c,b);ObjectNode ctx=Json.object().put("requestHash",hash);ctx.set("request",request.deepCopy());
            String id=addMessage(c,b,"admin",client,s(request,"content"),ctx,"queued",nullable(request,"replyToMessageId"));
            boolean immediateControl=false;java.sql.Savepoint actionStart=c.setSavepoint();
            try{
                if(!s(request,"expectedLockVersion").equals(s(build,"lock_version")))throw error(409,"STALE_BUILD","构建已更新，请刷新后重试");
                if(request.has("reviewContext"))reviewTarget(c,b,request);
                if("cancelled".equals(s(build,"status")))throw error(409,"BUILD_CANCELLED","构建已取消");
                if("completed".equals(s(build,"status")))throw error(409,"BUILD_COMPLETED","构建已完成，请新建构建修改");
                String action=request.path("action").path("type").asText();
                if(Set.of("pause","resume","cancel","retry").contains(action)){
                    ObjectNode intent=Json.object().put("intent",action).put("conditional",false).put("ambiguous",false).put("quoted",false);ObjectNode result=applyIntent(c,b,id,request,intent);finishMessage(c,id,intent,result,"succeeded");immediateControl=true;
                }else{
                    if(one(c,"SELECT id FROM ep_job WHERE build_id=? AND kind='interpret_message' AND status IN ('queued','running')",b)!=null)throw error(409,"MESSAGE_BUSY","上一条消息仍在处理，请等待");
                    queue.enqueue(c,b,"interpret_message",null,Json.object().put("messageId",id),"interpret:"+id);
                }
            }catch(ProductionException e){c.rollback(actionStart);finishMessage(c,id,null,Json.object().put("httpStatus",e.httpStatus).put("code",e.code).put("message",e.getMessage()),"failed");}
            event(c,b,"message.saved",Json.object().put("messageId",id));ObjectNode response=messageResponse(one(c,"SELECT * FROM ep_message WHERE id=?",id));if(immediateControl)response.put("httpStatus",202);return response;
        });
    }
    private ObjectNode messageResponse(ObjectNode m){ObjectNode out=Json.object().put("messageId",s(m,"id")).put("processingStatus",s(m,"processing_status")).put("httpStatus",m.path("result").path("httpStatus").asInt(202));out.set("result",m.path("result"));return out;}
    private void finishMessage(Connection c,String id,ObjectNode interpretation,ObjectNode result,String status)throws Exception{execute(c,"UPDATE ep_message SET interpretation=?,result=?,processing_status=?,updated_at=now() WHERE id=?",interpretation,result,status,id);}
    private ObjectNode reviewTarget(Connection c,String b,JsonNode request)throws Exception{
        JsonNode ctx=request.path("reviewContext");
        for(String field:List.of("artifactId","revisionId","presentedMessageId","contentSha256","scope"))if(s(ctx,field).isBlank())throw error(409,"UNPRESENTED_REVIEW","确认必须携带服务端展示身份");
        for(String field:List.of("artifactId","revisionId","presentedMessageId")){try{UUID.fromString(s(ctx,field));}catch(IllegalArgumentException e){throw error(409,"UNPRESENTED_REVIEW","展示身份格式无效");}}
        ObjectNode a=artifact(c,s(ctx,"artifactId"));
        if(a==null||!b.equals(s(a,"build_id"))||!s(a,"current_revision_id").equals(s(ctx,"revisionId"))||!s(a,"content_sha256").equals(s(ctx,"contentSha256"))||!"succeeded".equals(s(a,"generation_status"))||!"current".equals(s(a,"dependency_state")))throw error(409,"STALE_REVIEW_CONTEXT","成果已变化或尚未生成，请查看当前完整稿");
        ObjectNode shown=one(c,"SELECT context_snapshot FROM ep_message WHERE id=? AND build_id=? AND role='assistant'",s(ctx,"presentedMessageId"),b);
        JsonNode presentation=shown==null?Json.object():shown.path("context_snapshot").path("presentation");
        for(String field:List.of("artifactId","revisionId","contentSha256","scope"))if(!s(ctx,field).equals(s(presentation,field))||s(ctx,field).isBlank())throw error(409,"UNPRESENTED_REVIEW","确认必须对应服务端展示的完整版本");
        if(!ProductionRules.scope(s(a,"kind")).equals(s(ctx,"scope")))throw error(409,"INVALID_REVIEW_SCOPE","局部认可不能批准完整成果");return a;
    }

    private void workLoop(){String owner=Json.id();while(!closed&&!Thread.currentThread().isInterrupted()){
        try{var claim=queue.claim(owner,Duration.ofSeconds(30));if(claim==null){Thread.sleep(150);continue;}AtomicBoolean lost=new AtomicBoolean();ScheduledFuture<?> renewal=renewals.scheduleAtFixedRate(()->{try{if(!queue.renew(claim,Duration.ofSeconds(30)))lost.set(true);}catch(Exception e){lost.set(true);}},5,5,TimeUnit.SECONDS);
            try{String kind=s(claim.job(),"kind");if(kind.equals("prelearn"))prelearn(claim,lost);else if(kind.equals("interpret_message"))interpret(claim,lost);else generate(claim,lost);}
            catch(Exception e){try{if(!closed)failJob(claim,e);}catch(Exception ignored){ProductionDiagnostics.log("worker failure persistence",ignored);}}
            finally{renewal.cancel(false);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();break;}catch(Exception e){if(!closed){ProductionDiagnostics.log("worker",e);try{Thread.sleep(500);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}}}
    }}
    private ObjectNode call(String purpose,ObjectNode input,ProductionJobQueue.Claim claim,AtomicBoolean lost)throws Exception{
        java.util.function.BooleanSupplier cancelled=()->{if(closed||lost.get()||Thread.currentThread().isInterrupted())return true;try{return !queue.live(claim);}catch(Exception e){return true;}};
        if(cancelled.getAsBoolean())throw new CancellationException("任务已失去租约");
        try{if(!redis.permit("production","configured-model",Integer.parseInt(System.getenv().getOrDefault("EXPERT_MODEL_CALL_LIMIT","120")),Duration.ofMinutes(1)))throw new QuotaUnavailable();}
        catch(java.io.IOException e){throw new QuotaUnavailable();}
        ObjectNode result=model.json(purpose,input,cancelled);if(cancelled.getAsBoolean())throw new CancellationException("迟到模型结果已丢弃");return result;
    }
    private void failJob(ProductionJobQueue.Claim claim,Exception e)throws Exception{
        if(e instanceof QuotaUnavailable){queue.defer(claim,"共享额度暂不可用，保留任务等待重试",Duration.ofSeconds(30));db.locked(s(claim.job(),"build_id"),c->{if(one(c,"SELECT id FROM ep_job WHERE id=? AND status='queued' AND phase='quota_wait'",s(claim.job(),"id"))!=null)event(c,s(claim.job(),"build_id"),"job.quota_wait",Json.object().put("jobId",s(claim.job(),"id")));return null;});return;}
        if(!queue.live(claim))return;String safe=e instanceof IllegalArgumentException?e.getMessage():"处理失败（"+e.getClass().getSimpleName()+"），请重试或检查模型与存储连接";
        if(safe==null||safe.length()>300)safe="处理未完成，请重试";final String message=safe;
        queue.withLease(claim,c->{String b=s(claim.job(),"build_id");String r=nullable(claim.job(),"target_revision_id");if(r!=null)execute(c,"UPDATE ep_artifact_revision SET generation_status='failed',updated_at=now() WHERE id=? AND sealed_at IS NULL",r);
            String m=claim.job().path("input").path("messageId").asText("");if(!m.isBlank()){ObjectNode result=e instanceof ProductionModelException failure?failure.details():Json.object().put("message",message);finishMessage(c,m,null,result.put("httpStatus",500),"failed");}return null;});queue.fail(claim,safe);
        db.locked(s(claim.job(),"build_id"),c->{
            ObjectNode failure=e instanceof ProductionModelException modelFailure?modelFailure.details():Json.object().put("message",message);
            if(execute(c,"UPDATE ep_job SET error=? WHERE id=? AND status='failed' AND lease_epoch=?",failure,s(claim.job(),"id"),claim.epoch())==1)event(c,s(claim.job(),"build_id"),"job.failed",failure.deepCopy().put("jobId",s(claim.job(),"id")));return null;
        });
    }
    private static final class QuotaUnavailable extends Exception {}
    private void prelearn(ProductionJobQueue.Claim claim,AtomicBoolean lost)throws Exception{
        String b=s(claim.job(),"build_id");List<ObjectNode> units=db.read(c->query(c,"SELECT a.id,a.current_revision_id,r.generator_config,r.generation_status FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind='learning_unit' ORDER BY a.sort_no",b));
        for(ObjectNode unit:units){if("succeeded".equals(s(unit,"generation_status")))continue;
            ObjectNode input=db.read(c->{ObjectNode in=creation(c,b).deepCopy();in.put("buildId",b).put("batchNo",unit.path("generator_config").path("batchNo").asInt());in.set("passages",unit.path("generator_config").path("passages"));ArrayNode previous=Json.array();query(c,"SELECT r.body FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind='learning_unit' AND r.generation_status='succeeded' ORDER BY a.sort_no",b).forEach(r->previous.add(r.path("body")));in.set("previousUnits",ProductionContext.previousUnits(previous));return in;});
            queue.withLease(claim,c->{execute(c,"UPDATE ep_artifact_revision SET generation_status='running' WHERE id=? AND sealed_at IS NULL",s(unit,"current_revision_id"));return null;});
            ObjectNode raw=call("prelearn",input,claim,lost);ArrayNode passages=(ArrayNode)input.path("passages");final ObjectNode learned;
            try{learned=ProductionRules.normalizePrelearn(raw,passages);}
            catch(IllegalArgumentException invalid){System.err.println("Production prelearn validation shapes: "+ProductionRules.prelearnShape(raw));throw invalid;}
            Set<String> allowed=Knowledge.ids(passages),covered=new HashSet<>();for(JsonNode id:ProductionRules.array(learned.path("coverage"),"processedSourceIds",true))covered.add(id.asText());if(!allowed.equals(covered))throw new IllegalArgumentException("预学习覆盖不完整");
            for(JsonNode id:ProductionRules.array(learned,"sourceIds",true))if(!allowed.contains(id.asText()))throw new IllegalArgumentException("预学习引用越界");
            ArrayNode refs=Json.array();for(JsonNode p:passages)refs.add(Json.object().put("chunkId",s(p,"id")).put("startOffset",0).put("endOffset",s(p,"text").codePointCount(0,s(p,"text").length())).put("purpose","完整方法预学习"));
            queue.withLease(claim,c->{ObjectNode generated=Json.object().put("systemPrompt","");generated.set("body",learned);generated.set("sources",refs);seal(c,b,s(unit,"current_revision_id"),"learning_unit",generated,List.of());execute(c,"UPDATE ep_job SET checkpoint=? WHERE id=?",Json.object().put("lastCompletedBatch",input.path("batchNo").asInt()),s(claim.job(),"id"));event(c,b,"prelearning.progress",Json.object().put("batchNo",input.path("batchNo").asInt()));return null;});
        }
        queue.withLease(claim,c->{queue.complete(c,claim,Json.object().put("complete",true));execute(c,"UPDATE ep_build SET phase='summary' WHERE id=?",b);ObjectNode summary=one(c,"SELECT id FROM ep_artifact WHERE build_id=? AND kind='book_summary'",b);enqueueGeneration(c,b,artifact(c,s(summary,"id")),false);bump(c,b);event(c,b,"prelearning.completed",Json.object());return null;});
    }
    private ArrayNode plans(Connection c,String b)throws Exception{ObjectNode row=one(c,"SELECT r.body FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind='book_summary'",b);return row!=null&&row.path("body").path("specialists") instanceof ArrayNode p?p:Json.array();}
    private void enqueueGeneration(Connection c,String b,ObjectNode a,boolean revise)throws Exception{
        String kind="book_summary".equals(s(a,"kind"))?(revise?"revise_summary":"generate_summary"):(revise?"revise_artifact":"generate_artifact");
        queue.enqueue(c,b,kind,s(a,"current_revision_id"),Json.object().put("artifactId",s(a,"id")),"generation:"+s(a,"current_revision_id")+":"+Json.id());
    }
    private List<String> dependencies(Connection c,String b,ObjectNode a)throws Exception{
        List<String> deps=new ArrayList<>();String kind=s(a,"kind"),role=s(a,"agent_role");
        for(ObjectNode other:query(c,"SELECT a.*,r.review_status,r.generation_status FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.id<>? ORDER BY a.sort_no",b,s(a,"id"))){
            boolean take="learning_unit".equals(s(other,"kind"))||(!"book_summary".equals(kind)&&"book_summary".equals(s(other,"kind")))||((!"book_summary".equals(kind)&&!("agent".equals(kind)&&"specialist".equals(role)))&&other.path("sort_no").asInt()<a.path("sort_no").asInt());
            if(take){if(!"current".equals(s(other,"dependency_state"))||!"succeeded".equals(s(other,"generation_status"))||(!"learning_unit".equals(s(other,"kind"))&&!"completed".equals(s(other,"review_status"))))throw error(409,"DEPENDENCY_NOT_READY","上游成果尚未确认");deps.add(s(other,"current_revision_id"));}
        }return deps;
    }
    private void generate(ProductionJobQueue.Claim claim,AtomicBoolean lost)throws Exception{
        String b=s(claim.job(),"build_id"),r=s(claim.job(),"target_revision_id"),aId=claim.job().path("input").path("artifactId").asText();
        ObjectNode input=queue.withLease(claim,c->{ObjectNode a=artifact(c,aId);if(a==null||!r.equals(s(a,"current_revision_id")))throw new CancellationException("目标已更新");ObjectNode in=creation(c,b).deepCopy();in.put("buildId",b).put("kind",s(a,"kind")).put("agentRole",s(a,"agent_role")).put("logicalKey",s(a,"logical_key")).put("changeReason",s(a,"change_reason"));
            in.put("configuredModel",System.getenv().getOrDefault("EXPERT_MODEL","deepseek-v4-flash"));in.set("sources",sourceChunks(c,b));in.set("specialists",plans(c,b));
            ObjectNode rev=one(c,"SELECT * FROM ep_artifact_revision WHERE id=?",r);in.set("plan",currentPlan(c,b,a,rev.path("generator_config").path("plan")));String previous=nullable(rev,"based_on_revision_id");if(previous!=null)in.set("previousRevision",revision(c,one(c,"SELECT * FROM ep_artifact_revision WHERE id=?",previous)));else in.putNull("previousRevision");
            ArrayNode learning=Json.array(),approved=Json.array();for(ObjectNode other:query(c,"SELECT a.kind,a.logical_key,a.agent_role,a.current_revision_id,r.body,r.system_prompt,r.review_status FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? ORDER BY sort_no",b)){if("learning_unit".equals(s(other,"kind")))learning.add(other.path("body"));else if("completed".equals(s(other,"review_status")))approved.add(other);}in.set("learningUnits",learning);in.set("approvedArtifacts",approved);in.set("dependencyIds",Json.MAPPER.valueToTree(dependencies(c,b,a)));
            execute(c,"UPDATE ep_artifact_revision SET generation_status='running',updated_at=now() WHERE id=? AND sealed_at IS NULL",r);return in;});
        ObjectNode window=ProductionContext.generation(input);
        ObjectNode rawOutput=call("generate",window,claim,lost);final ObjectNode output;
        final ObjectNode normalizedSources;
        try{normalizedSources=ProductionRules.normalizeSources(rawOutput,(ArrayNode)window.path("sources"));}
        catch(IllegalArgumentException invalid){ObjectNode detail=Json.object().put("field","sources").put("type",rawOutput.path("sources").getNodeType().name());detail.set("items",ProductionRules.sourceShape(rawOutput,(ArrayNode)window.path("sources")));System.err.println("Production generation validation: "+detail);throw invalid;}
        try{output=ProductionRules.normalizeChapters(s(input,"kind"),normalizedSources,(ArrayNode)window.path("sources"));ProductionRules.validateGenerated(s(input,"kind"),output,(ArrayNode)window.path("sources"),(ArrayNode)input.path("specialists"));if("workbench".equals(s(input,"origin"))&&"book_summary".equals(s(input,"kind")))ProductionRules.validateProposalCandidates(output,(ArrayNode)window.path("sources"));}
        catch(IllegalArgumentException invalid){System.err.println("Production generation validation: "+(invalid instanceof ProductionRules.ValidationFailure failure?failure.details():Json.object().put("stage","artifact_constraints").put("errorType",invalid.getClass().getSimpleName())));throw invalid;}
        queue.withLease(claim,c->{ObjectNode a=artifact(c,aId);if(!r.equals(s(a,"current_revision_id")))throw new CancellationException("目标已更新");List<String> deps=dependencies(c,b,a);if(!Json.MAPPER.valueToTree(deps).equals(input.path("dependencyIds")))throw new CancellationException("上游版本已变化");
            if("team_manifest".equals(s(a,"kind"))){assertFinalReady(c,b,aId);((ObjectNode)output.path("body")).set("members",manifestMembers(c,b,aId));}
            seal(c,b,r,s(a,"kind"),output,deps);execute(c,"UPDATE ep_artifact SET dependency_state='current',updated_at=now() WHERE id=?",aId);execute(c,"UPDATE ep_build SET current_artifact_id=?,focus_revision_id=? WHERE id=?",aId,r,b);
            String shown=present(c,b,artifact(c,aId),"请审核当前完整成果、完整提示词与来源；未确认前不会推进。");
            for(JsonNode question:output.path("clarifications")){String q=question.asText().trim();if(!q.isEmpty())execute(c,"INSERT INTO ep_clarification(id,build_id,artifact_id,revision_id,kind,question,opened_message_id,created_by) VALUES(?,?,?,?,'requirement',?,?,?)",Json.id(),b,aId,r,q,shown,ACTOR);}
            queue.complete(c,claim,Json.object().put("revisionId",r));bump(c,b);event(c,b,"artifact.generated",Json.object().put("revisionId",r));return null;});
    }
    private void seal(Connection c,String b,String r,String kind,ObjectNode output,List<String> deps)throws Exception{
        ArrayNode sources=output.withArray("sources");List<JsonNode> sorted=new ArrayList<>();sources.forEach(sorted::add);sorted.sort(Comparator.comparing((JsonNode n)->s(n,"chunkId")).thenComparingInt(n->n.path("startOffset").asInt()));sources.removeAll();sorted.forEach(sources::add);List<String> sortedDeps=new ArrayList<>(deps);Collections.sort(sortedDeps);
        ObjectNode payload=Json.object().put("schemaVersion",1).put("kind",kind).put("systemPrompt",s(output,"systemPrompt"));payload.set("body",output.path("body"));payload.set("sources",sources);payload.set("dependencies",Json.MAPPER.valueToTree(sortedDeps));String hash=ProductionRules.hash(payload);
        int changed=execute(c,"UPDATE ep_artifact_revision SET body=?,system_prompt=?,content_sha256=?,generation_status='succeeded',sealed_at=now(),updated_at=now() WHERE id=? AND sealed_at IS NULL",output.path("body"),s(output,"systemPrompt"),hash,r);if(changed!=1)throw new CancellationException("成果已经封存");
        for(JsonNode source:sources){ObjectNode chunk=one(c,"SELECT c.document_version_id FROM ep_source_chunk c JOIN ep_build_document d ON d.document_version_id=c.document_version_id WHERE d.build_id=? AND c.id=?",b,s(source,"chunkId"));if(chunk==null)throw new IllegalArgumentException("来源不属于当前构建");execute(c,"INSERT INTO ep_revision_source(build_id,revision_id,document_version_id,chunk_id,start_offset,end_offset,purpose,created_by) VALUES(?,?,?,?,?,?,?,?)",b,r,s(chunk,"document_version_id"),s(source,"chunkId"),source.path("startOffset").asInt(),source.path("endOffset").asInt(),s(source,"purpose"),ACTOR);}
        for(String dep:deps)execute(c,"INSERT INTO ep_revision_dependency(build_id,revision_id,depends_on_revision_id,relation,created_by) VALUES(?,?,?,'derived_from',?)",b,r,dep,ACTOR);
    }
    private String present(Connection c,String b,ObjectNode a,String text)throws Exception{
        ObjectNode p=Json.object().put("artifactId",s(a,"id")).put("revisionId",s(a,"current_revision_id")).put("contentSha256",s(a,"content_sha256")).put("scope",ProductionRules.scope(s(a,"kind")));
        ObjectNode context=Json.object();context.set("presentation",p);String id=addMessage(c,b,"assistant",null,text,context,"succeeded",null);execute(c,"UPDATE ep_build SET waiting_question_message_id=? WHERE id=?",id,b);return id;
    }
    private ArrayNode manifestMembers(Connection c,String b,String exclude)throws Exception{return nodes(query(c,"SELECT a.id AS \"artifactId\",a.kind,a.logical_key AS \"logicalKey\",r.id AS \"revisionId\",r.content_sha256 AS \"contentSha256\" FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind<>'learning_unit' AND a.id<>? ORDER BY a.sort_no",b,exclude));}
    private void assertFinalReady(Connection c,String b,String manifest)throws Exception{
        if(one(c,"SELECT id FROM ep_clarification WHERE build_id=? AND status='open' LIMIT 1",b)!=null)throw error(409,"CLARIFICATION_OPEN","仍有必要澄清未解决");
        if(one(c,"SELECT a.id FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind<>'learning_unit' AND a.id<>? AND (a.dependency_state<>'current' OR r.review_status<>'completed' OR r.generation_status<>'succeeded') LIMIT 1",b,manifest)!=null)throw error(409,"INCOMPLETE_TEAM","必要成果尚未全部确认");
        for(String key:List.of("summary","fallback","router","keywords","qa"))if(one(c,"SELECT id FROM ep_artifact WHERE build_id=? AND logical_key=?",b,key)==null)throw error(409,"INCOMPLETE_TEAM","必要成果缺失");
        int specialists=one(c,"SELECT count(*) n FROM ep_artifact WHERE build_id=? AND agent_role='specialist'",b).path("n").asInt();if(specialists!=plans(c,b).size()||specialists==0)throw error(409,"INCOMPLETE_TEAM","专业分工清单不一致");
    }
    private void interpret(ProductionJobQueue.Claim claim,AtomicBoolean lost)throws Exception{
        String b=s(claim.job(),"build_id"),id=claim.job().path("input").path("messageId").asText();
        ObjectNode m=db.read(c->one(c,"SELECT * FROM ep_message WHERE id=?",id));ObjectNode request=(ObjectNode)m.path("context_snapshot").path("request");ObjectNode intent;
        if(request.path("action").isObject()){
            String type=request.path("action").path("type").asText();if(!Set.of("approve","reject","pause","resume","cancel","retry").contains(type))throw error(400,"INVALID_ACTION","操作类型无效");
            intent=Json.object().put("intent",type).put("scope",request.path("action").path("scope").asText()).put("targetRevisionId",request.path("reviewContext").path("revisionId").asText()).put("reason",request.path("reason").asText("")).put("reply","").put("conditional",false).put("ambiguous",false).put("quoted",false);intent.set("resolveClarificationIds",Json.array());
        }else{ObjectNode input=Json.object().put("buildId",b);ObjectNode message=request.deepCopy().put("id",id);input.set("message",message);input.set("snapshot",snapshot(b));ObjectNode window=ProductionContext.interpretation(input,db.read(c->sourceChunks(c,b)));intent=call("interpret",window,claim,lost);Json.required(intent,"intent",40);Json.required(intent,"reply",16000);ProductionRules.normalizeRewrite(intent,s(request,"content"));Set<String> visible=Knowledge.ids((ArrayNode)window.path("sources"));for(JsonNode source:intent.path("sourceIds"))if(!visible.contains(source.asText()))throw new IllegalArgumentException("解释引用未进入本次原文窗口");}
        ObjectNode interpretation=intent;
        queue.withLease(claim,c->{
            java.sql.Savepoint actionStart=c.setSavepoint();
            try{ObjectNode now=build(c,b);if(!s(now,"lock_version").equals(s(request,"expectedLockVersion")))throw error(409,"STALE_BUILD","处理期间构建发生变化，请查看新稿");
                ObjectNode result=applyIntent(c,b,id,request,interpretation);finishMessage(c,id,interpretation,result,"succeeded");
            }catch(ProductionException e){c.rollback(actionStart);finishMessage(c,id,interpretation,Json.object().put("httpStatus",e.httpStatus).put("code",e.code).put("message",e.getMessage()),"failed");}
            queue.complete(c,claim,Json.object().put("messageId",id));event(c,b,"message.processed",Json.object().put("messageId",id));return null;
        });
    }
    private ObjectNode applyIntent(Connection c,String b,String message,ObjectNode request,ObjectNode intent)throws Exception{
        String type=s(intent,"intent");ObjectNode state=build(c,b);String status=s(state,"status");
        if(status.equals("cancelled")||status.equals("completed"))throw error(409,"BUILD_TERMINAL","构建已结束");
        ObjectNode result=Json.object().put("httpStatus",200).put("type",type);
        // 必须先判定语义授权；连焦点切换、澄清关闭和控制任务也不能先执行。
        if(!ProductionRules.unambiguousIntent(intent)){
            addMessage(c,b,"assistant",null,"这条消息包含条件、引文或尚未明确的意图，请明确希望执行的操作与对象。"+(s(intent,"reply").isBlank()?"":"\n"+s(intent,"reply")),Json.object(),"succeeded",message);
            return result.put("type","clarify");
        }
        if(Set.of("pause","cancel","resume","retry").contains(type)){
            if(type.equals("pause")||type.equals("cancel")){queue.cancelGeneration(c,b);execute(c,"UPDATE ep_build SET status=? WHERE id=?",type.equals("pause")?"paused":"cancelled",b);execute(c,"UPDATE ep_artifact_revision SET generation_status='cancelled' WHERE build_id=? AND sealed_at IS NULL",b);
                if(type.equals("cancel")){
                    execute(c,"UPDATE ep_message SET processing_status='cancelled',result=?,updated_at=now() WHERE build_id=? AND id<>? AND processing_status IN ('queued','running')",Json.object().put("httpStatus",409).put("code","BUILD_CANCELLED").put("message","构建已取消"),b,message);
                    execute(c,"UPDATE ep_job SET status='cancelled',cancel_requested=true,phase='cancelled',lease_epoch=lease_epoch+1,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE build_id=? AND kind='interpret_message' AND status IN ('queued','running') AND input->>'messageId'<>?",b,message);
                }
            }
            else return resumeOrRetry(c,b,state,message,type);
            bump(c,b);addMessage(c,b,"assistant",null,type.equals("pause")?"已暂停生成，可继续提问或恢复。":type.equals("cancel")?"构建已取消，迟到生成结果不会写入。":"已恢复当前任务。",Json.object(),"succeeded",message);return result;
        }
        if(type.equals("ask")||type.equals("read_source")||type.equals("focus")){
            for(JsonNode source:intent.path("sourceIds"))if(one(c,"SELECT c.id FROM ep_source_chunk c JOIN ep_build_document d ON d.document_version_id=c.document_version_id WHERE d.build_id=? AND c.id=?",b,source.asText())==null)throw error(422,"SOURCE_OUTSIDE_BUILD","解释引用不属于本构建");
            String focus=s(intent,"targetRevisionId");if(type.equals("focus")&&!focus.isBlank()){if(one(c,"SELECT id FROM ep_artifact_revision WHERE build_id=? AND id=?",b,focus)==null)throw error(404,"NOT_FOUND","焦点不属于构建");execute(c,"UPDATE ep_build SET focus_revision_id=? WHERE id=?",focus,b);}
            List<String> definitionQuestions=new ArrayList<>();ObjectNode clarifiedArtifact=null;
            for(JsonNode questionId:intent.path("resolveClarificationIds")){
                ObjectNode q=one(c,"SELECT * FROM ep_clarification WHERE build_id=? AND id=? AND status='open'",b,questionId.asText());
                if(q!=null&&Set.of("requirement","source_conflict").contains(s(q,"kind"))){
                    ObjectNode target=reviewTarget(c,b,request);
                    if(!s(target,"id").equals(s(q,"artifact_id"))||!s(target,"current_revision_id").equals(s(q,"revision_id")))throw error(409,"STALE_CLARIFICATION","澄清必须对应当前展示版本");
                    clarifiedArtifact=target;definitionQuestions.add(s(q,"id"));
                }
            }
            resolveClarifications(c,b,message,request,intent,false);
            if(clarifiedArtifact!=null){
                revise(c,b,clarifiedArtifact,"管理员澄清："+s(request,"content"));String newRevision=s(artifact(c,s(clarifiedArtifact,"id")),"current_revision_id");
                for(String questionId:definitionQuestions)execute(c,"UPDATE ep_clarification SET resolution_revision_id=? WHERE id=?",newRevision,questionId);
                bump(c,b);result.put("type","clarification_revision_queued").put("revisionId",newRevision);
            }
            String answer=clarifiedArtifact!=null?"已记录澄清，新稿生成任务已排队；生成完成后请重新审核完整内容。":s(intent,"reply");addMessage(c,b,"assistant",null,answer.isBlank()?"请说明要查看或修改的对象和范围。":answer,Json.object(),"succeeded",message);return result;
        }
        if(!status.equals("active"))throw error(409,"BUILD_PAUSED","构建已暂停，请先恢复后再修改或批准");
        ObjectNode a=reviewTarget(c,b,request);String r=s(a,"current_revision_id");
        if(!s(intent,"targetRevisionId").equals(r)||!s(intent,"scope").equals(ProductionRules.scope(s(a,"kind"))))throw error(409,"INVALID_REVIEW_SCOPE","语义对象或范围与实际展示不一致");
        if(type.equals("approve")){
            if(!ProductionRules.unconditionalApproval(intent)){addMessage(c,b,"assistant",null,s(intent,"reply").isBlank()?"条件或局部认可不能完成本次审核，请说明具体修改意见。":s(intent,"reply"),Json.object(),"succeeded",message);return result.put("type","clarify");}
            if(!s(state,"current_artifact_id").equals(s(a,"id")))throw error(409,"NOT_CURRENT_REVIEW","只能批准当前待审成果");
            if(one(c,"SELECT id FROM ep_clarification WHERE artifact_id=? AND status='open' LIMIT 1",s(a,"id"))!=null)throw error(409,"CLARIFICATION_OPEN","请先解决必要澄清并重新审阅完整成果");
            if("completed".equals(s(a,"review_status")))throw error(409,"ALREADY_APPROVED","该版本已确认");
            if("team_manifest".equals(s(a,"kind"))){assertFinalReady(c,b,s(a,"id"));if(!manifestMembers(c,b,s(a,"id")).equals(a.path("body").path("members")))throw error(409,"STALE_MANIFEST","团队快照已变化");}
            review(c,b,message,request,a,"approve",null);execute(c,"UPDATE ep_artifact_revision SET review_status='completed',updated_at=now() WHERE id=?",r);advance(c,b,a);bump(c,b);
        }else if(Set.of("reject","revise","provide_reason").contains(type)){
            String reason=s(intent,"reason").trim();
            if(type.equals("reject"))review(c,b,message,request,a,"reject",reason.isBlank()?null:reason);
            if(reason.isBlank()){
                execute(c,"UPDATE ep_artifact_revision SET review_status='needs_reason' WHERE id=?",r);String question="请具体说明哪里不准确、遗漏了什么，或希望如何修改。";String q=addMessage(c,b,"assistant",null,question,Json.object(),"succeeded",message);
                if(one(c,"SELECT id FROM ep_clarification WHERE revision_id=? AND kind='rejection_reason' AND status='open'",r)==null)execute(c,"INSERT INTO ep_clarification(id,build_id,artifact_id,revision_id,kind,question,opened_message_id,created_by) VALUES(?,?,?,?,'rejection_reason',?,?,?)",Json.id(),b,s(a,"id"),r,question,q,ACTOR);
                execute(c,"UPDATE ep_build SET waiting_question_message_id=? WHERE id=?",q,b);bump(c,b);result.put("type","needs_reason");
            }else{
                if(type.equals("provide_reason"))review(c,b,message,request,a,"reject",reason);
                resolveClarifications(c,b,message,request,intent,true);
                execute(c,"UPDATE ep_clarification SET status='resolved',answer=?,resolved_message_id=?,updated_at=now() WHERE revision_id=? AND kind='rejection_reason' AND status='open'",reason,message,r);
                revise(c,b,a,reason);bump(c,b);result.put("type","revision_queued");
            }
        }else throw error(422,"UNKNOWN_INTENT","无法安全执行此意图，请明确对象和范围");
        String answer="revision_queued".equals(s(result,"type"))?"已记录修改意见，新稿生成任务已排队；生成完成后请重新审核完整内容。":s(intent,"reply");
        if(!answer.isBlank())addMessage(c,b,"assistant",null,answer,Json.object(),"succeeded",message);return result;
    }
    /** 构建锁内复用已有工作；新的clientId也不能为同一目标重复付费生成。 */
    private ObjectNode resumeOrRetry(Connection c,String b,ObjectNode state,String message,String type)throws Exception{
        boolean prelearning="prelearning".equals(s(state,"phase")),paused="paused".equals(s(state,"status"));
        ObjectNode artifact=prelearning?null:current(c,b);String revision=artifact==null?null:s(artifact,"current_revision_id");
        ObjectNode pending=prelearning?one(c,"SELECT id FROM ep_job WHERE build_id=? AND kind='prelearn' AND status IN ('queued','running') AND NOT cancel_requested LIMIT 1",b):
            one(c,"SELECT id FROM ep_job WHERE build_id=? AND target_revision_id=? AND kind<>'interpret_message' AND status IN ('queued','running') AND NOT cancel_requested LIMIT 1",b,revision);
        if(pending!=null){addMessage(c,b,"assistant",null,"当前任务仍在处理中，请等待完成，无需重复恢复或重试。",Json.object(),"succeeded",message);return Json.object().put("httpStatus",200).put("type","processing").put("jobId",s(pending,"id"));}
        if(artifact!=null&&"succeeded".equals(s(artifact,"generation_status"))){
            if(paused&&type.equals("resume")){execute(c,"UPDATE ep_build SET status='active' WHERE id=?",b);bump(c,b);}
            present(c,b,artifact,paused&&!type.equals("resume")?"当前已有可审稿，构建仍暂停；可明确恢复后继续审核。":"当前已有可审稿，请继续审核，无需重复生成。");
            return Json.object().put("httpStatus",200).put("type","review_ready");
        }
        if(type.equals("resume")&&!paused)throw error(409,"BUILD_NOT_PAUSED","当前构建未暂停；失败任务请明确重试。");
        if(type.equals("retry")){
            ObjectNode last=prelearning?one(c,"SELECT status FROM ep_job WHERE build_id=? AND kind='prelearn' ORDER BY created_at DESC,id DESC LIMIT 1",b):
                one(c,"SELECT status FROM ep_job WHERE build_id=? AND target_revision_id=? AND kind<>'interpret_message' ORDER BY created_at DESC,id DESC LIMIT 1",b,revision);
            if(last==null||!Set.of("failed","cancelled").contains(s(last,"status"))||(!prelearning&&(artifact==null||!Set.of("failed","cancelled").contains(s(artifact,"generation_status")))))throw error(409,"NOTHING_TO_RETRY","当前没有失败或已停止的生成工作可重试。");
        }
        if(!prelearning&&artifact==null)throw error(409,"NOTHING_TO_RESUME","当前没有可恢复的生成目标。");
        execute(c,"UPDATE ep_build SET status='active' WHERE id=?",b);
        if(prelearning)queue.enqueue(c,b,"prelearn",null,Json.object(),"prelearn:"+b+":"+Json.id());
        else{execute(c,"UPDATE ep_artifact_revision SET generation_status='queued' WHERE id=? AND sealed_at IS NULL",revision);enqueueGeneration(c,b,artifact,!s(artifact,"change_reason").isBlank());}
        bump(c,b);addMessage(c,b,"assistant",null,"已恢复当前任务。",Json.object(),"succeeded",message);return Json.object().put("httpStatus",200).put("type",type);
    }
    private void resolveClarifications(Connection c,String b,String message,ObjectNode request,ObjectNode intent,boolean revising)throws Exception{
        for(JsonNode id:intent.path("resolveClarificationIds")){ObjectNode q=one(c,"SELECT * FROM ep_clarification WHERE build_id=? AND id=? AND status='open'",b,id.asText());if(q==null)throw error(409,"STALE_CLARIFICATION","待答问题已变化");if("rejection_reason".equals(s(q,"kind"))&&!revising)throw error(409,"REVISION_REQUIRED","否决原因必须进入修订流程");execute(c,"UPDATE ep_clarification SET status='resolved',answer=?,resolved_message_id=?,updated_at=now() WHERE id=?",s(request,"content"),message,id.asText());}
    }
    private void review(Connection c,String b,String message,ObjectNode request,ObjectNode a,String decision,String reason)throws Exception{
        JsonNode ctx=request.path("reviewContext");execute(c,"INSERT INTO ep_review(id,build_id,revision_id,scope,decision,reason,actor_id,message_id,action_no,presented_message_id,reviewed_sha256,created_by) VALUES(?,?,?,?,?,?,?,?,0,?,?,?)",Json.id(),b,s(a,"current_revision_id"),s(ctx,"scope"),decision,reason,ACTOR,message,s(ctx,"presentedMessageId"),s(ctx,"contentSha256"),ACTOR);
    }
    private void revise(Connection c,String b,ObjectNode a,String reason)throws Exception{
        queue.cancelGeneration(c,b);String prior=s(a,"current_revision_id");execute(c,"UPDATE ep_artifact_revision SET review_status='changes_requested' WHERE id=?",prior);
        execute(c,"WITH RECURSIVE affected(id) AS (SELECT revision_id FROM ep_revision_dependency WHERE depends_on_revision_id=? UNION SELECT d.revision_id FROM ep_revision_dependency d JOIN affected x ON d.depends_on_revision_id=x.id) UPDATE ep_artifact SET dependency_state='stale',updated_at=now() WHERE build_id=? AND current_revision_id IN (SELECT id FROM affected)",prior,b);
        String next=Json.id();ObjectNode old=one(c,"SELECT generator_config FROM ep_artifact_revision WHERE id=?",prior);ObjectNode config=((ObjectNode)old.path("generator_config")).deepCopy();
        if("specialist".equals(s(a,"agent_role")))config.set("plan",currentPlan(c,b,a,config.path("plan")));
        execute(c,"INSERT INTO ep_artifact_revision(id,build_id,artifact_id,revision_no,based_on_revision_id,generator_config,change_reason,created_by) VALUES(?,?,?,?,?,?,?,?)",next,b,s(a,"id"),a.path("revision_no").asInt()+1,prior,config,reason,ACTOR);
        execute(c,"UPDATE ep_artifact SET current_revision_id=?,dependency_state='current' WHERE id=?",next,s(a,"id"));execute(c,"UPDATE ep_build SET current_artifact_id=?,focus_revision_id=?,phase=? WHERE id=?",s(a,"id"),next,phase(a),b);
        enqueueGeneration(c,b,artifact(c,s(a,"id")),true);
    }
    private static String phase(ObjectNode a){return switch(s(a,"kind")){case "book_summary"->"summary";case "agent"->s(a,"agent_role").equals("specialist")?"specialists":s(a,"agent_role");case "team_manifest"->"final_review";default->"production_config";};}
    private JsonNode currentPlan(Connection c,String b,ObjectNode a,JsonNode fallback)throws Exception{
        if(!"specialist".equals(s(a,"agent_role")))return fallback;
        String key=s(a,"logical_key").substring("specialist:".length());
        for(JsonNode plan:plans(c,b))if(key.equals(s(plan,"key")))return plan.deepCopy();
        throw error(409,"PLAN_CHANGED","当前概要已不包含此专家分工，请先核对分工清单");
    }
    private void advance(Connection c,String b,ObjectNode approved)throws Exception{
        String kind=s(approved,"kind");
        if(kind.equals("team_manifest")){execute(c,"UPDATE ep_build SET status='completed',manifest_revision_id=? WHERE id=?",s(approved,"current_revision_id"),b);execute(c,"UPDATE ep_team SET completed_build_id=?,lock_version=lock_version+1 WHERE id=(SELECT team_id FROM ep_build WHERE id=?)",b,b);addMessage(c,b,"assistant",null,"当前团队快照及全部必要成果已由管理员确认，专家生产完成。",Json.object(),"succeeded",null);return;}
        if(kind.equals("book_summary")){
            ArrayNode plans=plans(c,b);Set<String> keys=new HashSet<>();int i=0;
            for(JsonNode plan:plans){String key="specialist:"+s(plan,"key");keys.add(key);ObjectNode found=one(c,"SELECT id FROM ep_artifact WHERE build_id=? AND logical_key=?",b,key);if(found==null)newArtifact(c,b,"agent","specialist",key,200+i,Json.object().set("plan",plan));i++;}
            // 保留旧分工的历史，但已存在专家时禁止模型静默移除或重命名，要求明确处理。
            for(ObjectNode old:query(c,"SELECT logical_key FROM ep_artifact WHERE build_id=? AND agent_role='specialist'",b))if(!keys.contains(s(old,"logical_key")))throw error(409,"PLAN_CHANGE_REQUIRES_REVIEW","新版概要改变了已存在分工身份，请保留原key并明确修订职责");
        }
        ObjectNode pending=one(c,"SELECT a.id FROM ep_artifact a JOIN ep_artifact_revision r ON r.id=a.current_revision_id WHERE a.build_id=? AND a.kind<>'learning_unit' AND (r.review_status<>'completed' OR a.dependency_state='stale') ORDER BY a.sort_no LIMIT 1",b);
        if(pending==null){String role=s(approved,"agent_role");String nextKind,nextRole=null,nextKey;int order;
            if(kind.equals("agent")&&role.equals("specialist")){nextKind="agent";nextRole="fallback";nextKey="fallback";order=300;}
            else if(kind.equals("agent")&&role.equals("fallback")){nextKind="agent";nextRole="router";nextKey="router";order=400;}
            else if(kind.equals("agent")&&role.equals("router")){nextKind="keyword_rule";nextKey="keywords";order=500;}
            else if(kind.equals("keyword_rule")){nextKind="qa_example";nextKey="qa";order=600;}
            else{nextKind="team_manifest";nextKey="manifest";order=700;}
            String id=newArtifact(c,b,nextKind,nextRole,nextKey,order,Json.object());pending=Json.object().put("id",id);
        }
        ObjectNode next=artifact(c,s(pending,"id"));execute(c,"UPDATE ep_build SET current_artifact_id=?,focus_revision_id=?,phase=? WHERE id=?",s(next,"id"),s(next,"current_revision_id"),phase(next),b);
        if("stale".equals(s(next,"dependency_state"))){revise(c,b,next,"上游版本改变，依据当前已确认上游重新生成，保留无关内容。");}
        else if("succeeded".equals(s(next,"generation_status")))present(c,b,next,"请继续审核下一份完整成果。");else enqueueGeneration(c,b,next,false);
    }
}
