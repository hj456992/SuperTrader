package dev.garden;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class RanchSourcesTest {
    @Test void importingMemberSeparatesOwnSpeechFromNeighboringContextAndIsStable() throws Exception {
        var messages=(ArrayNode)Store.JSON.readTree("""
            [{"id":"1","text":"周六好吗","memberId":"甲","at":"2026-01-01","isMe":false},
             {"id":"2","text":"我周日有空","memberId":"乙","at":"2026-01-01","isMe":false},
             {"id":"3","text":"好的","memberId":"我","at":"2026-01-01","isMe":true}]
            """);
        var first=RanchSources.select("conversation","周末群",messages,"乙");
        assertEquals(3,first.size());
        assertEquals("context",first.get(0).path("speaker").asText());
        assertEquals("them",first.get(1).path("speaker").asText());
        assertEquals("me",first.get(2).path("speaker").asText());
        assertEquals(first.get(1).path("sourceKey"),RanchSources.select("conversation","周末群",messages,"乙").get(1).path("sourceKey"));
        assertNotEquals(first.get(1).path("sourceKey"),RanchSources.select("different","周末群",messages,"乙").get(1).path("sourceKey"));
    }
    @Test void noMatchingMemberDoesNotImportUnrelatedConversation() {
        var messages=Store.JSON.createArrayNode();messages.addObject().put("id","1").put("memberId","甲").put("text","你好");
        assertThrows(IllegalArgumentException.class,()->RanchSources.select("c","群",messages,"不存在"));
    }
    @Test void catalogKeepsExplicitPlatformAndDoesNotGuessMissingLegacyPlatform() {
        var person=Store.JSON.createObjectNode().put("id","same-id").put("name","同名会话");person.putArray("messages");
        person.put("platform","feishu");
        assertEquals("feishu",RanchSources.legacyDescriptor(person).path("platform").asText());
        person.put("platform","wechat");
        assertEquals("wechat",RanchSources.legacyDescriptor(person).path("platform").asText());
        person.remove("platform");
        var unknown=RanchSources.legacyDescriptor(person);
        assertEquals("other",unknown.path("platform").asText());
        assertEquals("legacy:same-id",unknown.path("id").asText());
    }
    @Test void platformFailureKeepsExplicitlyLabeledOfflineRecords() {
        var saved=Store.JSON.createObjectNode().put("id","saved").put("name","已存朋友").put("platform","feishu");saved.putArray("messages");
        var result=RanchSources.platformCatalog("feishu",java.util.List.of(saved),()->{throw new IllegalArgumentException("飞书未打开");});
        assertEquals(1,result.path("conversations").size());assertFalse(result.path("conversations").get(0).path("live").asBoolean());
        assertEquals("legacy:saved",result.path("conversations").get(0).path("id").asText());
        assertFalse(result.path("warnings").isEmpty());assertFalse(result.path("liveAvailable").asBoolean());
    }
}
