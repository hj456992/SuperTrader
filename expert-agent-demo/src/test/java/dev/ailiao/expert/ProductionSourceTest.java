package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionSourceTest {
    private ObjectNode normalize(ObjectNode source,String text)throws Exception{
        var method=ProductionRules.class.getDeclaredMethod("normalizeSources",ObjectNode.class,ArrayNode.class);method.setAccessible(true);
        ObjectNode output=Json.object();output.set("sources",Json.array().add(source));
        return (ObjectNode)method.invoke(null,output,Json.array().add(Json.object().put("id","source-1").put("text",text)));
    }
    private ObjectNode source(){return Json.object().put("chunkId","source-1").put("purpose","论证依据");}
    private void rejects(ObjectNode source,String text)throws Exception{
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->normalize(source,text));assertInstanceOf(IllegalArgumentException.class,error.getCause());
    }
    @Test void verbatimQuoteComputesCodePointRangeWithoutModelCounting()throws Exception{
        ObjectNode raw=source().put("quote","核实😀事实");var normalized=normalize(raw,"前😀：核实😀事实，再得出结论。").path("sources").get(0);
        assertEquals(3,normalized.path("startOffset").asInt());assertEquals(8,normalized.path("endOffset").asInt());
        assertFalse(normalized.has("quote"),"落库来源使用可重建的规范码点范围，不把原始模型额外字段混入hash");
        assertFalse(raw.has("startOffset"),"不修改原始模型输出");
    }
    @Test void absentOrAmbiguousQuotesAndForeignChunksAreRejected()throws Exception{
        rejects(source().put("quote","改写的句子"),"必须忠实原文");
        rejects(source().put("quote","事实"),"事实应有证据，事实不能猜测");
        rejects(source().put("quote","aa"),"aaa");
        rejects(source().put("quote","原文").put("chunkId","foreign"),"原文");
    }
    @Test void legalLegacyOffsetsRemainExactButInvalidOffsetsAreNeverClamped()throws Exception{
        var normalized=normalize(source().put("startOffset",1).put("endOffset",3),"甲😀乙丙").path("sources").get(0);
        assertEquals(1,normalized.path("startOffset").asInt());assertEquals(3,normalized.path("endOffset").asInt());
        rejects(source().put("startOffset",0).put("endOffset",99),"原文");
        rejects(source().put("startOffset",-1).put("endOffset",1),"原文");
        rejects(source().put("startOffset",0.5).put("endOffset",1.5),"原文");
        rejects(source().put("startOffset","0").put("endOffset","1"),"原文");
    }
    @Test void quoteCannotHideConflictingOffsetsOrSplitSurrogatePairs()throws Exception{
        rejects(source().put("quote","乙").put("startOffset",0).put("endOffset",1),"甲乙");
        rejects(source().put("quote","\uD83D"),"😀");
        rejects(source().put("quote","   "),"   ");
        var normalized=normalize(source().put("quote","乙").put("startOffset",1).put("endOffset",2),"甲乙").path("sources").get(0);
        assertEquals(1,normalized.path("startOffset").asInt());assertEquals(2,normalized.path("endOffset").asInt());
    }

    @Test void selectedSpansExtractOriginalTextIncludingOcrSpacingAndUnicode() throws Exception {
        ObjectNode raw=source();raw.set("spanIds",Json.array().add("s2").add("s3"));
        var normalized=assertDoesNotThrow(()->normalize(raw,"前😀"+"甲".repeat(37)+"。核实 事实"+"乙".repeat(34)+"。\n再得结论"+"丙".repeat(34)+"！")).path("sources").get(0);
        assertEquals(40,normalized.path("startOffset").asInt());assertEquals(120,normalized.path("endOffset").asInt());
        assertEquals("论证依据",normalized.path("purpose").asText());assertFalse(normalized.has("spanIds"));
    }
    @Test void spanIdentityDisambiguatesRepeatedOriginalSentences() throws Exception {
        ObjectNode raw=source();raw.set("spanIds",Json.array().add("s2"));
        var normalized=assertDoesNotThrow(()->normalize(raw,("原句".repeat(20)+"。").repeat(2))).path("sources").get(0);
        assertEquals(41,normalized.path("startOffset").asInt());assertEquals(82,normalized.path("endOffset").asInt());
    }
    @Test void invalidOrConflictingSpanSelectionsNeverFallBackToQuotes() throws Exception {
        for(ArrayNode ids:new ArrayNode[]{Json.array(),Json.array().add("s99"),Json.array().add("s1").add("s1"),Json.array().add(1)}) {
            ObjectNode raw=source();raw.set("spanIds",ids);rejects(raw,"甲。乙。丙。");
        }
        ObjectNode mixed=source().put("quote","甲。");mixed.set("spanIds",Json.array().add("s1"));rejects(mixed,"甲。乙。");
        mixed.remove("quote");mixed.put("startOffset",0).put("endOffset",2);rejects(mixed,"甲。乙。");
    }

    @Test void modelOptionsPreserveOriginalPreviewAndLongUnicodeTextWithoutMutation() {
        String text="😀".repeat(241)+"  原文。\n";ObjectNode chunk=Json.object().put("id","source-1").put("text",text).put("textIsPreview",true);
        ObjectNode window=Json.object();window.set("sources",Json.array().add(chunk));
        ObjectNode input=ProductionCitations.modelInput(window);var selected=input.path("sources").get(0);
        assertFalse(selected.has("text"));assertTrue(selected.path("textIsPreview").asBoolean());
        assertEquals(text,selected.path("citationSpans").findValuesAsText("text").stream().reduce("",String::concat));assertEquals(text,chunk.path("text").asText());
        assertEquals(240,ProductionCitations.spans(text).get(0).end());
        ObjectNode raw=source();raw.set("spanIds",Json.array().add("s2"));
        ObjectNode out=Json.object();out.set("sources",Json.array().add(raw));
        var range=ProductionRules.normalizeSources(out,(ArrayNode)window.path("sources")).path("sources").get(0);
        assertEquals(240,range.path("startOffset").asInt());assertEquals(247,range.path("endOffset").asInt());
        assertThrows(IllegalArgumentException.class,()->ProductionCitations.modelInput(window.deepCopy().put("oversize","大".repeat(90000))));
    }

    @Test void scatteredSelectionsProduceSeparateExactRangesWithoutFillingGaps() throws Exception {
        ObjectNode raw=source();raw.set("spanIds",Json.array().add("s4").add("s1").add("s2"));
        var refs=assertDoesNotThrow(()->normalize(raw,"甲".repeat(39)+"。"+"乙".repeat(39)+"。"+"不".repeat(39)+"。"+"丁".repeat(39)+"。")).path("sources");
        assertEquals(2,refs.size());assertEquals(0,refs.get(0).path("startOffset").asInt());assertEquals(80,refs.get(0).path("endOffset").asInt());
        assertEquals(120,refs.get(1).path("startOffset").asInt());assertEquals(160,refs.get(1).path("endOffset").asInt());
        assertEquals("论证依据",refs.get(1).path("purpose").asText());
    }

    @Test void punctuationDenseSourceCannotExplodeCitationWindow() {
        ObjectNode window=Json.object();window.set("sources",Json.array().add(Json.object().put("id","source-1").put("text","a;".repeat(4000))));
        ObjectNode adapted=assertDoesNotThrow(()->ProductionCitations.modelInput(window));
        assertTrue(adapted.toString().length()<18000);assertEquals("a;".repeat(4000),adapted.path("sources").get(0).path("citationSpans").findValuesAsText("text").stream().reduce("",String::concat));
    }
}
