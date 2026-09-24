package dev.garden;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class RanchKnowledgeTest {
    @Test void retrievalRanksRelevantChinesePassageAndKeepsProvenance() {
        var pages=Store.JSON.createArrayNode();
        pages.addObject().put("location","第1页").put("text","园艺需要适量浇水，阳光与土壤有利于植物生长。");
        pages.addObject().put("location","第2页").put("text","第一次认识朋友，可以从共同兴趣开始聊天。提出开放的问题，认真倾听对方表达，回应具体细节。");
        var chunks=RanchKnowledge.chunk("book-one","交流练习",pages);
        var result=RanchKnowledge.rank(chunks,"认识朋友 共同兴趣 倾听",3);
        assertEquals(1,result.size());
        assertEquals("第2页",result.get(0).path("location").asText());
        assertEquals("book-one",result.get(0).path("documentId").asText());
        assertTrue(result.get(0).path("id").asText().startsWith("K-book-one-"));
    }
    @Test void unrelatedQueryDoesNotInventBookEvidence() {
        var pages=Store.JSON.createArrayNode();pages.addObject().put("location","正文").put("text","汽车发动机的燃油系统。");
        assertTrue(RanchKnowledge.rank(RanchKnowledge.chunk("b","汽车",pages),"倾听与关系",4).isEmpty());
    }
    @Test void chunkingRetainsEndOfLongDocument() {
        var pages=Store.JSON.createArrayNode();pages.addObject().put("location","正文").put("text","朋友聊天。".repeat(450)+"结尾：每个人可以表达自己的边界。");
        var chunks=RanchKnowledge.chunk("b","书",pages);
        assertTrue(chunks.size()>2);
        assertTrue(chunks.get(chunks.size()-1).path("text").asText().contains("表达自己的边界"));
        assertTrue(chunks.get(0).path("text").asText().length()<=900);
    }
}
