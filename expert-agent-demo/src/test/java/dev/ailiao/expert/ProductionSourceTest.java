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
}
