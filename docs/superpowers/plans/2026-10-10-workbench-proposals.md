# Workbench Team Proposals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Selecting document versions produces candidate subexperts for ONE team before manual review, without duplicate prelearning.

**Architecture:** Add an idempotent proposal entry point into the existing ProductionService workflow. A dedicated workbench component observes selected versions and renders the existing summary draft; review uses the same build.

**Tech Stack:** Java17/Jackson/PostgreSQL, current DSH model adapter, plain browser JavaScript, node:test DOM harness.

## Global Constraints

- User explicitly approved same-team candidates and said implement directly. No new confirmation gate.
- Existing isolated worktree /Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/expert-production, module expert-agent-demo. Current baseline e598c4d.
- Preserve current user data, model/MinerU credentials, runtime. Do not restart the service or call real cloud/model from subagents.
- No new tables, infra, routing layer, approval bypass, or alternate LLM chain. Keep existing manual creation and old experts compatible.
- Candidate count1–6; candidates remain pending. Sources must belong to selected immutable versions. Do not generate template experts.
- TDD: run intended failing tests before implementation; record failures and green evidence privately under .local. Each implementation task commits its scoped files only, no push.
- Java tools: /Users/hou/.local/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2. Real tests use module `python3 .local/with-env.py` wrapper. Node /Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.
- Do not print credentials, private book text, full model outputs, signed URLs or environment config. Runtime is frozen outside module target, so module tests do not affect running server.

### Task 1: Durable proposal entry point and candidate source contract

**Files:** ProductionService.java, ProductionHttp.java, ProductionPrompts.java, optional focused ProductionProposals.java helper; new ProductionProposalTest.java using ProductionTestServer (do not alter shared fixture unless strictly necessary).

**Interfaces:**
- `POST /api/expert-production/v1/proposals` body `{documents:[{documentId,documentVersionId}]}`. Both IDs must be valid selected ready versions. Response same acceptedBuild (`buildId,status,phase,httpStatus=202,...`). No name/responsibility required.
- `ProductionService.propose(ObjectNode)` canonically sorts and validates document/version pairs, rejects duplicate document selections, empty/unknown/unready/mismatched selections. Uses server-generated name from document titles with safe length and generic responsibility to learn methods from all selected sources.
- Idempotency derives stable build/team identity from canonical version selection + local-admin + workbench schema version. Reordered equivalent selections and concurrent requests must not duplicate builds or jobs. Existing same-selection active/paused/failed/completed build is reused unchanged, never implicitly approved/restarted. A cancelled draft must not permanently block creating another analysis: deterministically derive next identity from prior cancelled build, reuse on repeated requests.
- Persist `origin:"workbench"` in creationRequest, and generated default name/responsibility. Existing create request fingerprint/semantics remain intact.
- Existing snapshot gains `proposal` ONLY for workbench origin: `{documents:[{documentId,documentVersionId}], sources:[{chunkId,documentVersionId,pageNo,title}]}`. sources contain metadata only for selected source chunks; no private text or credentials. Other snapshot fields unchanged.
- `snapshot.summary` existing revision supplies body.specialists. Workbench-mode generated summary specialists must additionally have nonempty `typicalQuestions` string array and `sourceIds` string array, bounded and validated against this generation input's real source chunk IDs. Each candidate at least1 source and1 question. Validate after existing generation/source checks, including revised summaries. Existing manually-created builds do not gain new required fields.
- Prompt explicitly describes ONE team, meaningful1–6 divisions, no forced count, source-based typical questions and IDs. In workbench mode existing `origin` must survive ProductionContext.generation (inspect and minimally fix if required).

- [ ] Add real DB/HTTP tests: select-only POST begins durable build; reordered requests same build/job; concurrent duplicate requests one build; changing selected version creates distinct draft; invalid/duplicate/mismatched selections rejected before creating records.
- [ ] Add model tests with controlled model specific to new test: full prelearn -> valid recommendation summary with candidate questions/sources; repeat propose reuses learned artifacts and no extra model calls; snapshot origins/docs/source metadata correct; ep_review count0 and no specialist agent artifacts before summary approved. Legacy create fixture and full flow remain green.
- [ ] Add invalid workbench candidate source test and cancelled-then-new-analysis test; verify all intended failures before implementation.
- [ ] Implement minimal entrypoint/helper, route, enriched snapshot and conditional validation/prompt. Keep ownership and existing leases intact.
- [ ] Run targeted tests with actual PG/Redis via wrapper, inspect counts, self-review and commit scoped changes. Report exact interface/evidence, no raw book content.

### Task 2: Automatic selection-to-candidates workbench

**Files:** new resources/web/workbench.js and workbench.css; app.js,index.html,production-api.js,LabHttp.java resource allowlist; new src/test/frontend/workbench-ui.test.cjs (reuse dom-harness.cjs, extend only generically if necessary).

**Interfaces:**
- Add `ProductionAPI.client(...).propose(documents)` calling POST /proposals with `{documents}`.
- `WorkbenchUI.create({root,client,onReview})` returns `setDocuments(documents)`, `enter()`, `leave()`; onReview(buildId) opens existing production UI using same build ID.
- Documents are existing state.documents with versions. Select documents/versions in new workbench component; render candidate cards from snapshot.summary.body.specialists (key,name,responsibility,methodTitles,typicalQuestions,sourceIds); source metadata in snapshot.proposal.sources.
- New-mode expert workbench shows selection FIRST, no required name/responsibility form. Preserve hidden legacy form IDs for old expert loadForm compatibility; old existing expert selection can still use legacy edit/generation. Avoid `.selection` class collision with legacy form.
- Source clicks call client.source(buildId,{chunkId}), show actual text and local PDF page link safely (textContent/escaped output; no model HTML execution or arbitrary hyperlinks).
- Debounce selection changes 1200ms while page active; never call model API for empty selection or unselected version-only changes. Every request/poll tagged to selection generation; old A results cannot overwrite newer B or empty state. Leaving cancels timers and invalidates UI delivery; do not auto cancel remote tasks. Re-enter retrieves same proposal.
- Persist selected document/version identities in localStorage with versioned key, defensively handle denied/corrupt storage; stale selections discarded (never silently replace selected version). After refresh entered workbench restores selections and requests idempotent proposal; no client authority for approval/state.
- Pending phase shows real prelearning progress from learning artifacts/jobs, and explains same-team draft/pending review. Loading must not claim completion. Poll active snapshot at2s; stop on succeeded summary/paused/failed/cancelled/completed; expose manual retry network and existing resume/retry actions with expectedLockVersion, stable request ID if response uncertain. Enter review action is always same build (no create); candidate edits are performed in existing review chat via “审阅并调整这支团队”. No implicit approval.
- Selecting different sources clearly says existing started drafts remain in production list; latest selection only shown. No silent old-candidate fallback on failure. Label1–6专业候选 + 后续兜底与主专家经审核生成.

- [ ] Write node:test for real component + client: empty never posts; rapid multi-select collapses; reordered selections no duplicate call; A late result cannot replace B/empty; changing selected version posts correct identity; stale saved versions ignored; refresh reuses server; pending->cards; true errors; source viewer actual returned text; review click passes existing buildId and calls neither create nor approve; paused/failure actions use exact revision-safe controls.
- [ ] Run tests red with missing component/features. Implement component and minimal app integration, resource registration, responsive cards and safety copy.
- [ ] Run all frontend tests + syntax checks, Java packaging resource check when task1 no longer running. Self-review, commit only scoped files, report evidence and concerns.

### Task 3: Integrated verification and delivery (controller)

**Files:** existing docs/expert-production/integration-results.md, acceptance-results.md, README(s); external architecture chapters13/16 and maintenance agreement.

- [ ] Review task outputs independently, resolve findings and verify only scoped changes.
- [ ] Run unfiltered Java suite with real PG/Redis and all frontend suites. Freeze matching source copy and package; preserve existing data when restarting same48763 instance after checking active jobs.
- [ ] Use actual browser current expert workbench: select user-uploaded source(s), wait real model coverage and candidate draft, inspect one source and enter SAME review build. Do not approve any real artifact or clear current data. Verify refresh persists candidate draft, zero approvals.
- [ ] Record test counts/source hash, actual preview and source read evidence; update status in architecture docs and regenerate HTML. Commit, push existing GitHub main and verify exact remote SHA, fast-forward clean outputs/demo checkout. Final concise Chinese with result/test/commit link.
