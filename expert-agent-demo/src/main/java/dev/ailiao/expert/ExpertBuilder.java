package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** 从全部所选文本提炼方法，再跨文档形成可追溯的专家分工。 */
final class ExpertBuilder {
    private final ModelCalls models;
    private final LabStore store;
    /** 接入模型和版本仓库。@param models 模型服务 @param store 仓库 */
    ExpertBuilder(ModelCalls models, LabStore store) { this.models = models; this.store = store; }
    /** 完整成功后一次性保存草稿。@param request 已选择的资料及职责 @param job 后台任务 */
    ObjectNode generate(ObjectNode request, Jobs.Job job) throws Exception {
        String name = Json.required(request, "name", 80);
        String duty = Json.required(request, "duty", 2000);
        ArrayNode chunks = store.selectedChunks(request.path("selections"));
        List<ArrayNode> batches = new ArrayList<>();
        ArrayNode batch = Json.array(); int chars = 0;
        for (JsonNode chunk : chunks) {
            if (chars + chunk.path("text").asText().length() > 18000 && !batch.isEmpty()) { batches.add(batch); batch = Json.array(); chars = 0; }
            batch.add(chunk); chars += chunk.path("text").asText().length();
        }
        if (!batch.isEmpty()) { batches.add(batch); }
        ArrayNode methods = Json.array(); ArrayNode outlines = Json.array();
        for (int i = 0; i < batches.size(); i++) {
            job.event("reading", "研读资料 " + (i + 1) + "/" + batches.size() + "，提炼主题与方法");
            ObjectNode input = Json.object().put("expertDuty", duty); input.set("passages", batches.get(i));
            ObjectNode extracted = models.json("你是资料研究员。通读本批全部片段，提炼与专家职责有关的完整方法；方法不是原文摘抄，需说明适用条件和局限。保留不同观点，不编造理论。输出 {\"summary\":\"本批主题概述\",\"methods\":[{\"title\":\"方法名\",\"when\":\"适用条件\",\"steps\":\"可执行步骤\",\"limits\":\"例外与局限\",\"sourceIds\":[\"实际片段id\"]}]}。methods 为 1–6 条；资料不相关时仍可提炼资料自身的方法并明确不适用当前职责。每条至少一个本批真实来源，禁止改写id。", input.toString(), job);
            Json.required(extracted, "summary", 2400);
            if (!(extracted.path("methods") instanceof ArrayNode values)) { throw new IllegalArgumentException("模型未返回方法列表"); }
            Knowledge.validateMethods(values, batches.get(i));
            outlines.add(extracted.path("summary"));
            for (JsonNode value : values) { ((ObjectNode) value).put("id", "m" + (methods.size() + 1)); methods.add(value); }
        }
        job.event("organizing", "归并跨文档方法，生成主专家与子 Agent 分工");
        ObjectNode input = Json.object().put("name", name).put("duty", duty); input.set("outlines", outlines); input.set("methods", methods);
        ObjectNode planned = models.json("设计一位专家及其专业子 Agent。根据专家职责和实际方法归并主题，不按页数或分块数量创建 Agent，不强行制造分工。输出 {\"summary\":\"专家能力和局限\",\"subagents\":[{\"name\":\"专业名称\",\"duty\":\"明确职责\",\"when\":\"主专家何时调用\",\"methodIds\":[\"m1\"]}]}。子 Agent 1–6 个，职责尽量不重复，每个必须绑定至少一个输入中存在的方法id。用中文。", input.toString(), job);
        Json.required(planned, "summary", 2400);
        if (!(planned.path("subagents") instanceof ArrayNode agents) || agents.isEmpty() || agents.size() > 6) { throw new IllegalArgumentException("模型返回的子 Agent 分工无效"); }
        Map<String, JsonNode> indexed = new HashMap<>(); methods.forEach(m -> indexed.put(m.path("id").asText(), m));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < agents.size(); i++) {
            ObjectNode agent = (ObjectNode) agents.get(i);
            String agentName = Json.required(agent, "name", 100);
            if (!names.add(agentName)) { throw new IllegalArgumentException("子 Agent 名称重复，请重新生成"); }
            Json.required(agent, "duty", 2000); Json.required(agent, "when", 1600);
            if (!agent.path("methodIds").isArray() || agent.path("methodIds").isEmpty()) { throw new IllegalArgumentException("子 Agent 缺少方法依据"); }
            ArrayNode assigned = Json.array(); Set<String> refs = new LinkedHashSet<>();
            for (JsonNode id : agent.path("methodIds")) {
                JsonNode method = indexed.get(id.asText());
                if (method == null) { throw new IllegalArgumentException("子 Agent 引用了不存在的方法"); }
                assigned.add(method.deepCopy()); method.path("sourceIds").forEach(ref -> refs.add(ref.asText()));
            }
            agent.put("id", "specialist_" + (i + 1)); agent.set("methods", assigned); agent.set("sourceIds", Json.MAPPER.valueToTree(refs)); agent.remove("methodIds");
        }
        ObjectNode spec = Json.object().put("name", name).put("duty", duty).put("summary", planned.path("summary").asText())
            .put("provider", models.provider).put("model", models.model).put("promptVersion", "expert-lab-1");
        spec.set("selections", request.path("selections").deepCopy()); spec.set("subagents", agents); spec.set("readingSummaries", outlines);
        spec.put("processedChunks", chunks.size()).put("methodCount", methods.size());
        job.event("saving", "来源校验通过，保存专家草稿");
        return job.commit(() -> store.saveExpert(request.path("expertId").asText(""), spec));
    }
}
