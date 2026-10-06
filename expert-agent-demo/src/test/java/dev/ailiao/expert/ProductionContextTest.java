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
    @Test void bookSummaryIncludesRepresentativeMethodsAndVerbatimEvidenceAcrossAllBatches() {
        ObjectNode input=unevenBook();
        ObjectNode context=ProductionContext.generation(input);
        assertEquals(11,context.path("learningUnits").size());
        for(int batch=0;batch<11;batch++) {
            assertTrue(context.path("relevantMethods").findValuesAsText("title").contains("代表方法"+batch),"完整方法缺少批次"+batch);
            assertTrue(context.path("sources").findValuesAsText("id").contains("batch-"+batch),"原文证据缺少批次"+batch);
        }
        assertTrue(context.toString().length()<=90000);
        assertTrue(context.path("relevantMethods").toString().length()<=18000);
        assertTrue(context.path("sources").toString().length()<=18000);
        for(var source:context.path("sources")) {
            String original=input.path("sources").get(Integer.parseInt(source.path("id").asText().substring(6))).path("text").asText();
            String excerpt=source.path("text").asText();
            assertFalse(excerpt.isEmpty());
            assertTrue(original.startsWith(excerpt),"引用窗口必须逐字保留原文，不能追加提示文本");
            assertTrue(source.path("textIsPreview").asBoolean());
            assertEquals(original.length(),source.path("originalTextChars").asInt());
            assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length()-1)),"不可拆开Unicode码点");
            ObjectNode generated=Json.object().set("sources",Json.array().add(Json.object().put("chunkId",source.path("id").asText()).put("quote",excerpt).put("purpose","概要依据")));
            var normalized=ProductionRules.normalizeSources(generated,(ArrayNode)input.path("sources")).path("sources").get(0);
            assertEquals(0,normalized.path("startOffset").asInt());
            assertEquals(excerpt.codePointCount(0,excerpt.length()),normalized.path("endOffset").asInt());
        }
        assertFalse(context.path("contextCoverage").path("allOriginalTextIncluded").asBoolean(),"所有片段都有摘录也不代表全文进入窗口");
    }
    @Test void seeingExcerptOfEveryChunkDoesNotClaimAllOriginalTextWasIncluded() {
        ObjectNode input=unevenBook();((ArrayNode)input.path("sources")).remove(11);
        ObjectNode context=ProductionContext.generation(input);
        assertEquals(11,context.path("contextCoverage").path("selectedSourceChunks").asInt());
        assertEquals(11,context.path("contextCoverage").path("totalSourceChunks").asInt());
        assertFalse(context.path("contextCoverage").path("allOriginalTextIncluded").asBoolean());
        assertFalse(input.path("sources").get(0).has("textIsPreview"),"窗口不能修改已保存原文");
    }
    @Test void specialistKeepsDesiredLateBatchMethodAndItsFullEvidenceFirst() {
        ObjectNode input=unevenBook().put("kind","agent").put("agentRole","specialist");
        input.set("plan",Json.object().set("methodTitles",Json.array().add("代表方法10")));
        ObjectNode context=ProductionContext.generation(input);
        assertEquals("代表方法10",context.path("relevantMethods").get(0).path("title").asText());
        assertEquals("batch-10",context.path("sources").get(0).path("id").asText());
        assertEquals(input.path("sources").get(10),context.path("sources").get(0),"专业方法引用优先保留完整片段");
    }
    @Test void overBudgetMethodsDoNotEraseBatchSummariesOrGetMislabelledAsComplete() {
        ObjectNode input=unevenBook();
        for(var unit:input.path("learningUnits")) for(var method:unit.path("methods")) ((ObjectNode)method).put("steps","完整且不能截断".repeat(4000));
        ObjectNode context=ProductionContext.generation(input);
        assertEquals(11,context.path("learningUnits").size());
        assertEquals(0,context.path("relevantMethods").size());
        assertEquals(0,context.path("contextCoverage").path("fullMethodsInContext").asInt());
        assertEquals(11,context.path("contextCoverage").path("totalLearningUnits").asInt());
        assertTrue(context.toString().length()<=90000);
    }
    @Test void summaryStillIncludesEvidenceForProcessedBatchWithoutMethods() {
        ObjectNode input=unevenBook();ObjectNode last=(ObjectNode)input.path("learningUnits").get(10);
        last.set("methods",Json.array());last.remove("sourceIds");
        last.set("coverage",Json.object().put("noMethodReason","本批是附录，无独立方法").set("processedSourceIds",Json.array().add("batch-10")));
        ObjectNode context=ProductionContext.generation(input);
        assertTrue(context.path("sources").findValuesAsText("id").contains("batch-10"));
        assertEquals("本批是附录，无独立方法",context.path("learningUnits").get(10).path("noMethodReason").asText());
        assertEquals(11,context.path("learningUnits").size());
    }
    @Test void batchLimitationsSurviveEvenWhenSummaryAndMethodWindowDoNotMentionThem() {
        ObjectNode input=unevenBook();ObjectNode last=(ObjectNode)input.path("learningUnits").get(10);
        ArrayNode limitations=Json.array().add("第301页图表识别缺失，无法确认图中的分支条件。").add("需要复核的关键限制。".repeat(200));
        last.set("coverage",Json.object().set("limitations",limitations));
        ((ObjectNode)last.path("methods").get(0)).put("steps","完整方法不能裁剪".repeat(4000));
        ObjectNode context=ProductionContext.generation(input);
        assertFalse(context.path("relevantMethods").findValuesAsText("title").contains("代表方法10"));
        assertEquals("批次10涵盖独立主题",context.path("learningUnits").get(10).path("summary").asText());
        assertEquals(limitations,context.path("learningUnits").get(10).path("limitations"),"真实学习缺口不可因摘要未重复或方法未入窗而丢失，也不能裁剪");
    }
    @Test void criticalLimitationsExceedingTotalWindowRejectGenerationRatherThanDisappear() {
        ObjectNode input=unevenBook();ObjectNode last=(ObjectNode)input.path("learningUnits").get(10);
        last.set("coverage",Json.object().set("limitations",Json.array().add("识别缺失需人工复核".repeat(12000))));
        assertThrows(IllegalArgumentException.class,()->ProductionContext.generation(input));
    }
    @Test void missingOrNullBatchLimitationsDoNotCreateNullContextFields() {
        ObjectNode input=unevenBook();
        ((ObjectNode)input.path("learningUnits").get(1)).set("coverage",Json.object());
        ((ObjectNode)input.path("learningUnits").get(2)).set("coverage",Json.object().putNull("limitations"));
        ((ObjectNode)input.path("learningUnits").get(3)).set("coverage",Json.object().set("limitations",Json.array()));
        ObjectNode context=ProductionContext.generation(input);
        for(int i=0;i<3;i++)assertFalse(context.path("learningUnits").get(i).has("limitations"));
        assertEquals(Json.array(),context.path("learningUnits").get(3).path("limitations"));
    }
    @Test void approvedBookSummaryKeepsCompleteCorrectionsAndScopeWithoutItsSystemPrompt() {
        ObjectNode input=unevenBook().put("kind","agent");
        ObjectNode body=Json.object().put("title","已确认概要").put("summary","前部叙述".repeat(180)+"管理员纠正：后半部演示方法也属于生产范围。");
        body.set("outline",Json.array().add(Json.object().put("title","后半部演示").put("summary","纠正后的完整主题")));
        body.set("limitations",Json.array().add("管理员确认图表识别缺失仍需复核"));
        body.set("specialists",Json.array().add(Json.object().put("key","presentation").put("responsibility","负责演示结构").set("methodTitles",Json.array().add("演示方法"))));
        ObjectNode approved=Json.object().put("kind","book_summary").put("review_status","completed").put("system_prompt","不应传递的历史系统指令".repeat(10000));approved.set("body",body);
        input.set("approvedArtifacts",Json.array().add(approved));
        ObjectNode context=ProductionContext.generation(input);
        var retained=context.path("approvedArtifacts").get(0);
        assertEquals(body,retained.path("body"),"已确认概要的范围及纠正不得被摘要投影丢弃");
        assertFalse(retained.path("contextIsSummary").asBoolean(true));
        assertFalse(retained.has("system_prompt"));
        assertTrue(context.toString().length()<=90000);
    }
    @Test void approvedBookSummaryTooLargeForWindowIsRejectedRatherThanSilentlyShortened() {
        ObjectNode input=unevenBook();
        ObjectNode approved=Json.object().put("kind","book_summary").put("review_status","completed");
        approved.set("body",Json.object().put("summary","确认范围不可裁剪".repeat(14000)));
        input.set("approvedArtifacts",Json.array().add(approved));
        assertThrows(IllegalArgumentException.class,()->ProductionContext.generation(input));
    }
    @Test void manifestReceivesApprovedKeywordRulesAndQaRoutingWithoutHistoricalPrompts() {
        ObjectNode input=unevenBook().put("kind","team_manifest");
        ObjectNode keywords=Json.object().put("multiMatch","L3").put("noMatch","L2");
        keywords.set("rules",Json.array().add(Json.object().put("specialistKey","presentation").set("keywords",Json.array().add("演示").add("幻灯片"))));
        ObjectNode qa=Json.object().put("metric","cosine").put("threshold",0.90).put("comparison",">").put("multiMatch","L3").put("noMatch","L3").put("embeddingProvider","aliyun-bailian");
        qa.set("examples",Json.array().add(Json.object().put("specialistKey","presentation").put("question","怎样安排演示结构？").put("answer","先确认主张，再组织支撑。".repeat(100)).set("sourceIds",Json.array().add("batch-10"))));
        ArrayNode approved=Json.array();
        for(String kind:java.util.List.of("keyword_rule","qa_example")) {
            ObjectNode artifact=Json.object().put("kind",kind).put("review_status","completed").put("system_prompt","已审核但不应重复的历史提示词".repeat(10000));
            artifact.set("body","keyword_rule".equals(kind)?keywords:qa);approved.add(artifact);
        }
        input.set("approvedArtifacts",approved);
        ObjectNode context=ProductionContext.generation(input);
        assertEquals(keywords,context.path("approvedArtifacts").get(0).path("body"),"L1关键词及多命中/无命中分支不能丢失");
        assertEquals(qa,context.path("approvedArtifacts").get(1).path("body"),"L2样例、严格阈值比较及分支不能丢失");
        for(var artifact:context.path("approvedArtifacts")) {
            assertFalse(artifact.path("contextIsSummary").asBoolean(true));
            assertFalse(artifact.has("system_prompt"));
        }
        assertTrue(context.toString().length()<=90000);
    }
    @Test void approvedRoutingBodyCannotBeSilentlyShortenedToFitWindow() {
        for(String kind:java.util.List.of("keyword_rule","qa_example")) {
            ObjectNode input=unevenBook().put("kind","team_manifest");
            ObjectNode body=Json.object().set("keyword_rule".equals(kind)?"rules":"examples",Json.array().add("已确认规则或样例不可静默裁剪".repeat(10000)));
            ObjectNode approved=Json.object().put("kind",kind).put("review_status","completed");approved.set("body",body);
            input.set("approvedArtifacts",Json.array().add(approved));
            assertThrows(IllegalArgumentException.class,()->ProductionContext.generation(input),kind);
        }
    }
    private static ObjectNode unevenBook() {
        ObjectNode input=Json.object().put("kind","book_summary");
        ArrayNode units=Json.array(),sources=Json.array();
        for(int batch=0;batch<11;batch++) {
            String id="batch-"+batch;
            sources.add(Json.object().put("id",id).put("pageNo",batch*30+1).put("chapterPath","章"+batch).put("text","批次"+batch+"的逐字证据。"+"😀证据".repeat(700)));
            ArrayNode methods=Json.array();
            if(batch==0) for(int i=0;i<8;i++) methods.add(Json.object().put("title","首批大方法"+i).put("steps","前部完整步骤".repeat(900)).set("sourceIds",Json.array().add("preface")));
            methods.add(Json.object().put("title","代表方法"+batch).put("steps","完整代表步骤".repeat(130)).set("sourceIds",Json.array().add(id)));
            ObjectNode unit=Json.object().put("summary","批次"+batch+"涵盖独立主题");unit.set("methods",methods);unit.set("sourceIds",Json.array().add(id));units.add(unit);
        }
        sources.add(Json.object().put("id","preface").put("text","首批其他方法的序言证据".repeat(400)));
        input.set("learningUnits",units);input.set("sources",sources);input.set("approvedArtifacts",Json.array());return input;
    }

}
