package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionPrelearnTest {
    private ObjectNode normalize(ObjectNode input,ArrayNode passages)throws Exception{
        var method=ProductionRules.class.getDeclaredMethod("normalizePrelearn",ObjectNode.class,ArrayNode.class);method.setAccessible(true);
        return (ObjectNode)method.invoke(null,input,passages);
    }
    private ObjectNode draft(ObjectNode method){ObjectNode out=Json.object().put("summary","本批完整学习摘要");out.set("methods",Json.array().add(method));return out;}
    private ObjectNode method(){ObjectNode m=Json.object().put("title","完整方法").put("when","有证据时").put("limits","证据不足先澄清");m.set("sourceIds",Json.array().add("s1"));return m;}
    @Test void orderedTextStepsPreserveEveryElementAndOriginalArray()throws Exception{
        ArrayNode steps=Json.array().add("核实事实\n保留原始证据").add("说明结论").add("核对边界");ObjectNode m=method();m.set("steps",steps);
        ObjectNode out=normalize(draft(m),Json.array().add(Json.object().put("id","s1")));
        assertTrue(out.path("methods").get(0).path("steps").isTextual());
        assertEquals(steps,out.path("methods").get(0).path("stepsOriginal"));
        assertTrue(out.path("methods").get(0).path("steps").asText().contains("核实事实\n保留原始证据"));
        assertTrue(m.path("steps").isArray(),"不能原地修改供应商响应");
    }
    @Test void longCompleteStepsAndThirteenValidSourcesAreNotTruncated()throws Exception{
        String steps="完整方法步骤".repeat(900);ObjectNode m=method().put("steps",steps);ArrayNode passages=Json.array(),refs=Json.array();
        for(int i=0;i<13;i++){passages.add(Json.object().put("id","s"+i));refs.add("s"+i);}m.set("sourceIds",refs);
        ObjectNode out=normalize(draft(m),passages);
        assertEquals(steps,out.path("methods").get(0).path("steps").asText());assertEquals(13,out.path("methods").get(0).path("sourceIds").size());
    }
    @Test void malformedStepElementsAndForeignEvidenceStillFail()throws Exception{
        ObjectNode bad=method();bad.set("steps",Json.array().add(Json.object().put("unknown","不允许无约定结构")));
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->normalize(draft(bad),Json.array().add(Json.object().put("id","s1"))));assertInstanceOf(IllegalArgumentException.class,failure.getCause());
        bad.put("steps","合法步骤");bad.set("sourceIds",Json.array().add("foreign"));
        failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->normalize(draft(bad),Json.array().add(Json.object().put("id","s1"))));assertInstanceOf(IllegalArgumentException.class,failure.getCause());
    }
    private ObjectNode noMethods(){ObjectNode out=Json.object().put("summary","本批仅包含书末参考资料与致谢，没有可提取的方法。");out.set("methods",Json.array());out.set("sourceIds",Json.array().add("s1"));ObjectNode coverage=Json.object().put("noMethodReason","书末参考资料与致谢，没有方法步骤。");coverage.set("processedSourceIds",Json.array().add("s1"));coverage.set("limitations",Json.array());out.set("coverage",coverage);return out;}
    @Test void emptyMethodsWithExplicitReasonAndFullCoverageArePreserved()throws Exception{
        ObjectNode raw=noMethods();ObjectNode result=normalize(raw,Json.array().add(Json.object().put("id","s1")));assertEquals(raw,result);assertTrue(result.path("methods").isEmpty());
    }
    @Test void emptyMethodsCannotOmitReasonOrCoverageOrInventEvidence()throws Exception{
        for(int shape=0;shape<6;shape++){
            ObjectNode raw=noMethods();ObjectNode coverage=(ObjectNode)raw.path("coverage");
            switch(shape){case 0->coverage.remove("noMethodReason");case 1->coverage.put("noMethodReason"," ");case 2->coverage.put("noMethodReason",7);case 3->coverage.set("processedSourceIds",Json.array());case 4->coverage.set("processedSourceIds",Json.array().add("foreign"));case 5->raw.set("sourceIds",Json.array().add("foreign"));}
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->normalize(raw,Json.array().add(Json.object().put("id","s1"))));assertInstanceOf(IllegalArgumentException.class,error.getCause());
        }
    }
    @Test void summaryContextKeepsNoMethodBatchCoverageAndReason()throws Exception{
        ObjectNode input=Json.object();input.set("learningUnits",Json.array().add(noMethods()));input.set("sources",Json.array().add(Json.object().put("id","s1").put("text","参考书目与致谢")));input.set("approvedArtifacts",Json.array());
        ObjectNode context=ProductionContext.generation(input);assertEquals(1,context.path("learningUnits").size());assertEquals(0,context.path("learningUnits").get(0).path("methodCount").asInt(-1));assertEquals("书末参考资料与致谢，没有方法步骤。",context.path("learningUnits").get(0).path("noMethodReason").asText());assertEquals(1,context.path("learningUnits").get(0).path("processedSourceCount").asInt());assertTrue(context.path("relevantMethods").isEmpty());assertEquals(1,context.path("contextCoverage").path("totalLearningUnits").asInt());assertEquals(0,context.path("contextCoverage").path("totalMethods").asInt(-1));
    }

}
