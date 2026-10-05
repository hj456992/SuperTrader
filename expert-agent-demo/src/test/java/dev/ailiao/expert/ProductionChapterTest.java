package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionChapterTest {
    private ArrayNode chunks(){return Json.array().add(Json.object().put("id","one").put("documentVersionId","document-a").put("pageNo",3).put("text","先核实事实，再形成结论。").put("chapterPath",""));}
    private ObjectNode output(){
        ObjectNode body=Json.object();for(String key:java.util.List.of("name","description","summary","responsibility","model","overlapAnalysis"))body.put(key,"有效内容");
        body.set("tools",Json.array());body.set("capabilities",Json.array().add("核实事实"));body.set("boundaries",Json.array().add("不能猜测"));body.set("chapters",Json.array());
        ObjectNode out=Json.object().put("systemPrompt","完整提示词");out.set("body",body);out.set("sources",Json.array().add(Json.object().put("chunkId","one").put("startOffset",0).put("endOffset",5).put("purpose","方法依据")));out.set("clarifications",Json.array());return out;
    }
    private ObjectNode normalize(ObjectNode raw,ArrayNode chunks)throws Exception{
        try{return (ObjectNode)ProductionRules.class.getDeclaredMethod("normalizeChapters",String.class,ObjectNode.class,ArrayNode.class).invoke(null,"agent",raw,chunks);}
        catch(NoSuchMethodException e){return raw;}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw e;}
    }
    @Test void absentEmptyAndNullChaptersUseOnlyVerifiedSourceLocations()throws Exception{
        for(int variant=0;variant<3;variant++){
            ObjectNode raw=output();if(variant==1)((ObjectNode)raw.path("body")).remove("chapters");if(variant==2)((ObjectNode)raw.path("body")).putNull("chapters");
            ObjectNode result=normalize(raw,chunks());ProductionRules.validateGenerated("agent",result,chunks(),Json.array());
            assertEquals(Json.array().add("文档版本 document-a：未识别章节，第3页"),result.path("body").path("chapters"));assertEquals(raw.path("sources"),result.path("sources"));
            assertEquals(variant==0?Json.array():variant==1?MissingNode.getInstance():NullNode.getInstance(),raw.path("body").path("chapters"),"不修改原始模型输出");
        }
    }
    @Test void chapterFallbackDistinguishesDocumentsAndPreservesKnownMetadata()throws Exception{
        ArrayNode chunks=chunks();chunks.add(Json.object().put("id","two").put("documentVersionId","document-b").put("pageNo",3).put("text","先核实事实，再形成结论。").put("chapterPath","第二章 / 事实核对"));
        ObjectNode raw=output();raw.withArray("sources").add(Json.object().put("chunkId","two").put("startOffset",0).put("endOffset",5).put("purpose","补充依据"));
        ObjectNode result=normalize(raw,chunks);assertEquals(Json.array().add("文档版本 document-a：未识别章节，第3页").add("文档版本 document-b：第二章 / 事实核对，第3页"),result.path("body").path("chapters"));assertEquals(raw.path("sources"),result.path("sources"));
        ((ObjectNode)raw.path("body")).set("chapters",Json.array().add("模型已给出的定位"));assertEquals(raw,normalize(raw,chunks));
    }
    @Test void noFallbackForInventedSourcesInvalidPagesOrWrongChapterTypes()throws Exception{
        ObjectNode raw=output();((ObjectNode)raw.path("sources").get(0)).put("chunkId","foreign");assertThrows(IllegalArgumentException.class,()->normalize(raw,chunks()));
        ArrayNode bad=chunks();((ObjectNode)bad.get(0)).put("pageNo",0);assertThrows(IllegalArgumentException.class,()->normalize(output(),bad));
        for(var invalid:java.util.List.of(TextNode.valueOf("猜测章节"),Json.array().add(4),Json.array().add(" "))){ObjectNode wrong=output();((ObjectNode)wrong.path("body")).set("chapters",invalid);assertThrows(IllegalArgumentException.class,()->{var normalized=normalize(wrong,chunks());ProductionRules.validateGenerated("agent",normalized,chunks(),Json.array());});}
    }
    @Test void validationDiagnosticsIdentifyActualFieldWithoutText()throws Exception{
        ObjectNode raw=output();((ObjectNode)raw.path("body")).set("chapters",Json.array().add(4));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ProductionRules.validateGenerated("agent",raw,chunks(),Json.array()));
        ObjectNode detail=(ObjectNode)error.getClass().getDeclaredMethod("details").invoke(error);
        assertEquals("chapters",detail.path("field").asText());assertEquals("ARRAY",detail.path("type").asText());assertFalse(detail.toString().contains("有效内容"));
    }
}
