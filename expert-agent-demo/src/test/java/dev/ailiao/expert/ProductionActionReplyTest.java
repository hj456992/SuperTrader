package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static dev.ailiao.expert.ProductionAcceptanceTest.*;

/** Real HTTP and PostgreSQL; only the external semantic/model response is controlled. */
@Timeout(90)
class ProductionActionReplyTest {
    @TempDir Path root;
    private static final String CONTRADICTORY_REPLY="尚未执行修改，还需要确认是否按此方向修订。";

    @ParameterizedTest
    @ValueSource(strings={"修改：明确不可把猜测当事实","不通过：明确不可把猜测当事实","补充原因：明确不可把猜测当事实"})
    void queuedRevisionReportsRecordedActionInsteadOfModelConfirmationRequest(String text)throws Exception{
        try(var test=server(false)){
            String build=create(test);test.service.start();ObjectNode before=ready(test,build,"");
            if(text.startsWith("补充原因")){
                submit(test,build,before,"不通过", "needs_reason");before=test.snapshot(build);
                assertEquals("needs_reason",revision(before).path("reviewStatus").asText());
                assertTrue(before.path("messages").findValuesAsText("content").stream().anyMatch(s->s.contains("请具体说明")));
                assertEquals(1,current(before).path("revisions").size(),"无原因否决只能追问，不能生成新稿");
            }
            long lastSeq=lastSeq(before);String oldRevision=revision(before).path("id").asText();
            submit(test,build,before,text,"revision_queued");
            ObjectNode after=ready(test,build,oldRevision);
            assertEquals(2,current(after).path("revisions").size());
            assertEquals("pending",revision(after).path("reviewStatus").asText());
            assertTrue(revision(after).path("changeReason").asText().contains("不可把猜测当事实"));
            assertEquals(0,count(test,"ep_review",build,"decision='approve'"));
            assertActionReply(after,lastSeq);
        }
    }

    @Test void resolvedDefinitionReportsQueuedRevisionInsteadOfModelConfirmationRequest()throws Exception{
        try(var test=server(true)){
            String build=create(test);test.service.start();ObjectNode summary=ready(test,build,"");approve(test,build,summary);
            ObjectNode before=ready(test,build,revision(summary).path("id").asText());
            assertTrue(before.path("clarifications").findValuesAsText("status").contains("open"));
            long lastSeq=lastSeq(before);String oldRevision=revision(before).path("id").asText();
            submit(test,build,before,"仅用于内部汇报","clarification_revision_queued");
            ObjectNode after=ready(test,build,oldRevision);
            assertEquals("pending",revision(after).path("reviewStatus").asText());
            assertTrue(revision(after).path("systemPrompt").asText().contains("仅用于内部汇报"));
            assertEquals(0,count(test,"ep_clarification",build,"status='open'"));
            assertEquals(1,count(test,"ep_review",build,"decision='approve'"),"澄清不得批准新稿");
            assertActionReply(after,lastSeq);
        }
    }

    @Test void ordinaryQuestionKeepsModelExplanationWithoutCreatingRevision()throws Exception{
        try(var test=server(false)){
            String build=create(test);test.service.start();ObjectNode before=ready(test,build,"");
            long lastSeq=lastSeq(before);
            submit(test,build,before,"为什么先核实事实？","ask");ObjectNode after=test.snapshot(build);
            assertEquals(revision(before).path("id"),revision(after).path("id"));
            assertEquals(List.of("测试专用模型公开答复"),replies(after,lastSeq));
        }
    }

    private ProductionTestServer server(boolean clarify)throws Exception{
        var test=new ProductionTestServer(root);test.model.clarifyFirstAgent=clarify;
        ProductionModel model=(purpose,input,cancelled)->{
            ObjectNode out=test.model.json(purpose,input,cancelled);
            if(purpose.equals("interpret")&&(!out.path("reason").asText().isBlank()||!out.path("resolveClarificationIds").isEmpty()))out.put("reply",CONTRADICTORY_REPLY);
            return out;
        };
        test.service=new ProductionService(test.db,model,test.legacy,test.redis,root.resolve("shared-fixture"));
        test.services.add(test.service);test.base=test.serve(test.service);return test;
    }
    private static void submit(ProductionTestServer test,String build,ObjectNode state,String text,String resultType)throws Exception{
        var response=test.request("POST","/builds/"+build+"/messages",request(state,text,null));
        assertEquals(202,response.status());String id=response.body().path("messageId").asText();
        ObjectNode after=await(test,build,s->StreamSupport.stream(s.path("messages").spliterator(),false).anyMatch(m->m.path("id").asText().equals(id)&&List.of("succeeded","failed").contains(m.path("processingStatus").asText())));
        JsonNode message=StreamSupport.stream(after.path("messages").spliterator(),false).filter(m->m.path("id").asText().equals(id)).findFirst().orElseThrow();
        assertEquals("succeeded",message.path("processingStatus").asText(),message.toString());
        assertEquals(resultType,message.path("result").path("type").asText(),message.toString());
    }
    private static long lastSeq(ObjectNode state){return StreamSupport.stream(state.path("messages").spliterator(),false).mapToLong(m->m.path("seq").asLong()).max().orElse(0);}
    private static List<String> replies(ObjectNode state,long after){return StreamSupport.stream(state.path("messages").spliterator(),false).filter(m->m.path("seq").asLong()>after&&m.path("role").asText().equals("assistant")&&!m.has("presentation")).map(m->m.path("content").asText()).toList();}
    private static void assertActionReply(ObjectNode state,long after){
        List<String> replies=replies(state,after);assertEquals(1,replies.size());String reply=replies.get(0);
        assertNotEquals(CONTRADICTORY_REPLY,reply,"已提交的修订不能继续声称尚未执行或需要再次确认");
        assertTrue(reply.contains("已记录")&&reply.contains("新稿")&&reply.contains("审核"),reply);
    }
}
