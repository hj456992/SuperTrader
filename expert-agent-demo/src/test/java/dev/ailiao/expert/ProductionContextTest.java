package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionContextTest {
    @Test void semanticConversationGetsRealEvidenceAndBoundedHistory() throws Exception {
        ObjectNode input=Json.object();ObjectNode snapshot=Json.object().put("phase","summary");
        ArrayNode messages=Json.array();for(int i=0;i<300;i++)messages.add(Json.object().put("id","message-"+i).put("content","旧会话".repeat(600)));
        snapshot.set("messages",messages);snapshot.set("jobs",Json.array());snapshot.set("artifacts",Json.array());snapshot.set("clarifications",Json.array());snapshot.set("currentArtifact",Json.object());
        input.set("snapshot",snapshot);input.set("message",Json.object().put("content","解释原文中的条件").put("replyToMessageId","message-0"));
        ArrayNode sources=Json.array().add(Json.object().put("id","original-id").put("text","必须先核对证据，不得把推断当作事实。"));
        var method=ProductionContext.class.getDeclaredMethod("interpretation",ObjectNode.class,ArrayNode.class);method.setAccessible(true);
        ObjectNode context=(ObjectNode)method.invoke(null,input,sources);
        assertTrue(context.toString().length()<90000);
        assertTrue(context.path("sources").toString().contains("必须先核对证据"));
        assertTrue(context.path("snapshot").path("messages").findValuesAsText("id").contains("message-0"),"被回复消息不能因截取最近历史而消失");
        assertTrue(context.path("snapshot").path("messages").findValuesAsText("id").contains("message-299"));
        assertEquals("解释原文中的条件",context.path("message").path("content").asText());
    }
    @Test void largeBookDoesNotRepeatEverySourceOrEveryApprovedPrompt() throws Exception {
        ObjectNode input=Json.object().put("kind","agent").put("agentRole","specialist");input.set("plan",Json.object().set("methodTitles",Json.array().add("方法0")));
        ArrayNode sources=Json.array(),units=Json.array(),approved=Json.array();
        for(int i=0;i<120;i++)sources.add(Json.object().put("id","source-"+i).put("text","原文证据".repeat(500)));
        for(int i=0;i<16;i++){ObjectNode method=Json.object().put("title","方法"+i).put("when","适用条件").put("steps","完整步骤".repeat(200)).put("limits","边界");method.set("sourceIds",Json.array().add("source-"+i));ObjectNode unit=Json.object().put("summary","本批覆盖摘要".repeat(100));unit.set("methods",Json.array().add(method));units.add(unit);}
        for(int i=0;i<8;i++){ObjectNode a=Json.object().put("system_prompt","旧提示词".repeat(6000));a.set("body",Json.object().put("name","已批准专家"+i).put("responsibility","已有职责"));approved.add(a);}
        input.set("sources",sources);input.set("learningUnits",units);input.set("approvedArtifacts",approved);
        Class<?> type=assertDoesNotThrow(()->Class.forName("dev.ailiao.expert.ProductionContext"),"生产上下文必须有明确窗口构建器");
        var method=type.getDeclaredMethod("generation",ObjectNode.class);method.setAccessible(true);ObjectNode compact=(ObjectNode)method.invoke(null,input);
        assertTrue(compact.toString().length()<90000,"单次生成上下文必须有界");
        assertEquals(16,compact.path("learningUnits").size(),"全批覆盖摘要不能静默丢失");
        assertTrue(compact.path("sources").size()<sources.size());
        assertEquals(120,compact.path("contextCoverage").path("totalSourceChunks").asInt());
        assertTrue(compact.path("sources").findValuesAsText("id").contains("source-0"));
        assertFalse(compact.toString().contains("旧提示词旧提示词"));
        assertTrue(compact.path("relevantMethods").toString().contains("完整步骤"));
    }
}
