package com.supertrader.demo.taskcenter;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * The ONLY tools registered into the AgentScope task-center toolkit (Module 9).
 *
 * <p>Every method is read-only, deterministic and offline: it echoes,
 * explains or searches the local knowledge base — it NEVER places, cancels or
 * recovers any order, NEVER computes backtest metrics (those come only from
 * the deterministic Backtest Runner) and NEVER fakes model answers. The
 * {@link TaskCenterToolGuard} middleware independently enforces the same
 * six-name allowlist at call time, and the ToolProxy gates every harness
 * capability call.
 */
public class TaskCenterTools {

    private final KnowledgeService knowledge;

    public TaskCenterTools(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Tool(name = "strategy_read",
          description = "Read the completeness / missing-fields summary of a strategy Draft.",
          readOnly = true)
    public String strategyRead(
            @ToolParam(name = "draftId", description = "draft id") String draftId,
            @ToolParam(name = "completeness", description = "0-100 completeness") String completeness,
            @ToolParam(name = "missingFields", description = "comma-separated missing fields") String missingFields) {
        return "Draft " + nullSafe(draftId) + " completeness=" + nullSafe(completeness)
                + "% missing=[" + nullSafe(missingFields) + "]";
    }

    @Tool(name = "strategy_draft_update",
          description = "Show the deterministic constraints of a Draft field.",
          readOnly = true)
    public String draftUpdate(
            @ToolParam(name = "field", description = "field name") String field,
            @ToolParam(name = "value", description = "proposed value") String value) {
        return "Field " + nullSafe(field) + " value=" + ToolProxy.maskSensitive(nullSafe(value))
                + " — 必须通过确定性校验后才能确认（每轮最多追问一个最高影响字段）。";
    }

    @Tool(name = "strategy_validate",
          description = "Echo the deterministic Validator outcome.",
          readOnly = true)
    public String validate(
            @ToolParam(name = "templateType", description = "template type") String templateType,
            @ToolParam(name = "valid", description = "true/false") String valid,
            @ToolParam(name = "backtestable", description = "true/false") String backtestable) {
        return "Validator: template=" + nullSafe(templateType)
                + " valid=" + nullSafe(valid) + " backtestable=" + nullSafe(backtestable)
                + " — 校验结果由确定性 Validator 产生，模型无权修改。";
    }

    @Tool(name = "rag_search_local",
          description = "Search the LOCAL authorized knowledge base (rebuild/knowledge). Returns citations.",
          readOnly = true)
    public String searchKnowledge(
            @ToolParam(name = "query", description = "search keywords") String query) {
        if (query == null || query.isBlank()) {
            return "EVIDENCE_MISSING：检索关键词为空";
        }
        try {
            java.util.List<KnowledgeService.KnowledgeHit> hits = knowledge.search(query);
            if (hits.isEmpty()) {
                return "EVIDENCE_MISSING：本地知识库没有匹配内容，不凭空补全。";
            }
            StringBuilder sb = new StringBuilder();
            for (KnowledgeService.KnowledgeHit h : hits) {
                sb.append("[citation sourceId=").append(h.sourceId())
                  .append(" title=").append(h.title())
                  .append(" path=").append(h.relativePath())
                  .append(" sha256=").append(h.contentHash().substring(0, 12))
                  .append("] ").append(h.snippet()).append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "EVIDENCE_MISSING：" + ToolProxy.maskSensitive(e.getMessage());
        }
    }

    @Tool(name = "backtest_plan",
          description = "Explain the backtest-task gates (deterministic guidance only).",
          readOnly = true)
    public String planBacktest(
            @ToolParam(name = "draftId", description = "draft id") String draftId,
            @ToolParam(name = "datasetId", description = "dataset id") String datasetId) {
        return "回测任务创建门：草案必须已批准冻结且确定性校验通过（backtestable），"
                + "dataset=" + nullSafe(datasetId) + "；创建后仍需人工审批与人工启动，"
                + "系统不提供自动启动。draftId=" + nullSafe(draftId);
    }

    @Tool(name = "backtest_result_summarize",
          description = "Explain EXISTING structured backtest metrics without recomputing them.",
          readOnly = true)
    public String summarizeResult(
            @ToolParam(name = "runId", description = "backtest run id") String runId,
            @ToolParam(name = "status", description = "run status") String status,
            @ToolParam(name = "netReturnPct", description = "net return %") String netReturnPct,
            @ToolParam(name = "maxDrawdownPct", description = "max drawdown %") String maxDrawdownPct,
            @ToolParam(name = "winRate", description = "win rate %") String winRate) {
        return "回测运行 " + nullSafe(runId) + " 状态=" + nullSafe(status)
                + "：netReturnPct=" + nullSafe(netReturnPct)
                + " maxDrawdownPct=" + nullSafe(maxDrawdownPct)
                + " winRate=" + nullSafe(winRate)
                + " —— 以上数值来自确定性本地 Backtest Runner，解释不得修改数值；"
                + "内置数据集为验收样本，非真实行情，不构成投资建议。";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
