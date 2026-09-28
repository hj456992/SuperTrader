package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTest {
    @Test void splittingPreservesAllNonWhitespaceCharactersAndPageNumbers() {
        String content="先确认目标。\n\n".repeat(900);
        ArrayNode chunks=Knowledge.chunks("doc","v1","资料",Json.array().add(Json.object().put("page",7).put("text",content)));
        StringBuilder joined=new StringBuilder();
        chunks.forEach(c->{assertEquals(7,c.path("page").asInt());assertTrue(c.path("text").asText().length()<=2400);joined.append(c.path("text").asText());});
        assertEquals(content.replaceAll("\\s", ""),joined.toString().replaceAll("\\s", ""));
    }
    @Test void unknownModelCitationsAreRejectedRatherThanSilentlyKept() {
        ArrayNode chunks=Knowledge.chunks("doc","v1","资料",Json.array().add(Json.object().put("page",1).put("text","原文")));
        ObjectNode method=Json.object().put("title","方法").put("when","适用场景").put("steps","步骤").put("limits","边界");
        method.set("sourceIds",Json.array().add("invented-id"));
        assertThrows(IllegalArgumentException.class,()->Knowledge.validateMethods(Json.array().add(method), chunks));
    }
    @Test void searchSelectsRelevantChinesePassagesWithoutIntroducingOtherSources() {
        ArrayNode pages=Json.array().add(Json.object().put("page",1).put("text","天气晴朗，适合旅行。")).add(Json.object().put("page",2).put("text","谈判需要明确底线与替代方案，准备协商目标。"));
        ArrayNode chunks=Knowledge.chunks("doc","v1","资料",pages);
        ArrayNode hits=Knowledge.search(chunks,"谈判底线",1);
        assertEquals(2,hits.get(0).path("page").asInt());
    }
}
