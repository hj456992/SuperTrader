package dev.garden;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantStateTest {
    private ObjectNode json(String value) throws Exception { return (ObjectNode) Store.JSON.readTree(value); }
    private ObjectNode group() throws Exception {
        return json("""
            {"id":"chat-one","name":"周末群","source":"feishu_desktop","messages":[
              {"id":"a","sender":"小王","role":"note","source":"feishu_desktop","text":"周六有空吗"},
              {"id":"b","sender":"小李","role":"note","source":"feishu_desktop","text":"我周日有空"},
              {"id":"c","sender":"发言人未显示","role":"note","source":"feishu_desktop","text":"好的"}]}
            """);
    }
    private ObjectNode result() throws Exception {
        return json("""
            {"summary":{"text":"正在商量周末活动时间","evidenceIds":["a","b"]},
             "response":{"status":"needed","reason":"小王在询问时间","evidenceIds":["a"],"reply":"周六可以"},
             "profiles":[{"memberId":"sender:小王","overview":{"text":"提出周六安排","evidenceIds":["a"]},
               "traits":[{"text":"本次提出周六活动","kind":"explicit","evidenceIds":["a"]}],
               "uncertainty":"尚不知道长期时间偏好","change":"首次了解"}]}
            """);
    }
    private void focus(ObjectNode p) throws Exception {
        AssistantState.configure(p, json("""
            {"conversationType":"auto","selfId":"","focusIds":["sender:小王"]}
            """));
    }
    @Test void groupsKeepMembersSeparateAndUnknownSendersOutOfProfiles() throws Exception {
        var context = AssistantState.describe(group());
        assertEquals("group", context.path("type").asText());
        assertEquals(2, context.path("members").size());
        assertEquals("sender:小王", context.path("members").get(0).path("id").asText());
        assertEquals(1, context.path("members").get(0).path("messageIds").size());
    }
    @Test void directChatUsesOnlyOtherPersonsWordsAndAutoFocusesThem() throws Exception {
        var p = json("""
            {"name":"小林","messages":[{"id":"a","role":"them","text":"想拍夜景"},
              {"id":"b","role":"me","text":"好"},{"id":"c","role":"note","text":"备注"}]}
            """);
        var context = AssistantState.describe(p);
        assertEquals("direct", context.path("type").asText());
        assertEquals("counterpart", context.path("focusIds").get(0).asText());
        assertEquals("a", context.path("members").get(0).path("messageIds").get(0).asText());
    }
    @Test void unknownGroupIdentityCannotCreateRequiredReplyOrDraft() throws Exception {
        var p = group(); focus(p); var r = result();
        AssistantState.validate(r,p);
        assertEquals("unknown",r.path("response").path("status").asText());
        assertEquals("",r.path("reply").asText());
    }
    @Test void knownIdentityPreservesReplyDecision() throws Exception {
        var p = group(); focus(p);
        ((ObjectNode)p.path("assistantSettings")).put("selfId","sender:小李");
        var r=result(); AssistantState.validate(r,p);
        assertEquals("needed",r.path("response").path("status").asText());
        assertEquals("周六可以",r.path("reply").asText());
    }
    @Test void rejectsAnotherMembersEvidenceAsSoleBasisOfProfile() throws Exception {
        var p=group(); focus(p); var r=result();
        ((ObjectNode)r.path("profiles").get(0).path("overview")).putArray("evidenceIds").add("b");
        assertThrows(IllegalArgumentException.class,()->AssistantState.validate(r,p));
    }
    @Test void rejectsInventedEvidenceAndUnrequestedProfiles() throws Exception {
        var p=group();focus(p);var r=result();
        ((ObjectNode)r.path("summary")).putArray("evidenceIds").add("fake");
        assertThrows(IllegalArgumentException.class,()->AssistantState.validate(r,p));
        var other=result();((ObjectNode)other.path("profiles").get(0)).put("memberId","sender:小李");
        assertThrows(IllegalArgumentException.class,()->AssistantState.validate(other,p));
    }
    @Test void rejectsUnknownFocusAndSelfFocus() throws Exception {
        var p=group();
        assertThrows(IllegalArgumentException.class,()->AssistantState.configure(p,json("""
            {"conversationType":"auto","selfId":"","focusIds":["fake"]}
            """)));
        assertThrows(IllegalArgumentException.class,()->AssistantState.configure(p,json("""
            {"conversationType":"auto","selfId":"sender:小王","focusIds":["sender:小王"]}
            """)));
    }
    @Test void manualGroupCanIdentifyItsExplicitMeRole() throws Exception {
        var p=json("""
            {"name":"群","messages":[{"id":"a","sender":"甲","source":"manual_group","role":"them","text":"来吗"},
             {"id":"b","sender":"乙","source":"manual_group","role":"them","text":"好的"},
             {"id":"c","role":"me","text":"晚点答复"}]}
            """);
        AssistantState.configure(p,json("""
            {"conversationType":"group","selfId":"me","focusIds":["sender:甲"]}
            """));
        assertTrue(AssistantState.describe(p).path("identityKnown").asBoolean());
    }
    @Test void roleOnlyFollowupBelongsToExistingNamedDirectCounterpart() throws Exception {
        var p=group();
        AssistantState.configure(p,json("""
            {"conversationType":"direct","selfId":"sender:小李","focusIds":[]}
            """));
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.path("messages")).addObject()
            .put("id","followup").put("role","them").put("text","周六下午吧");
        var c=AssistantState.describe(p);
        assertEquals("sender:小王",c.path("focusIds").get(0).asText());
        assertEquals(2,c.path("members").get(0).path("messageIds").size());
        assertEquals(2,c.path("members").size());
    }
    @Test void profileHistoryPersistsAcrossUpdatesButOriginalCorrectionsRevokeIt() throws Exception {
        var p=group();focus(p);var r=result();AssistantState.validate(r,p);
        r.put("id","analysis-1").put("createdAt","2026-09-23T10:00:00Z");
        AssistantState.record(p,r);
        AssistantState.invalidate(p,false);
        assertTrue(p.path("profiles").path("sender:小王").path("stale").asBoolean());
        assertEquals(1,p.path("profiles").path("sender:小王").path("history").size());
        var r2=result(); AssistantState.validate(r2,p);r2.put("id","analysis-2");AssistantState.record(p,r2);
        assertEquals(2,p.path("profiles").path("sender:小王").path("history").size());
        assertFalse(p.path("profiles").path("sender:小王").path("stale").asBoolean());
        AssistantState.invalidate(p,true);
        assertTrue(p.path("analysis").isNull());
        assertEquals(0,p.path("profiles").size());
    }
}
