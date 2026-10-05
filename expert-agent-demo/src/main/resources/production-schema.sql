-- 专家生产：PostgreSQL 首次建表资源，2026-10-05；由 ProductionDatabase.migrate 在专用 schema 事务执行。
-- 15 表沿用 revisions/13.md；UUID 全由应用提供，无扩展、无触发器。
-- 仅空 schema 首次执行；运行时剥离 BEGIN/COMMIT，由 JDBC 迁移事务统一管理。
-- 循环：先建 team/build/artifact/revision/message，最后 ALTER 添加反向指针。
-- 插入：team(完成指针NULL) -> build(各指针NULL) -> artifact(当前指针NULL)
-- -> revision -> message -> 更新各指针；不需要关闭FK或延迟检查。
-- 冗余 build_id/document_version_id 仅为复合FK限定归属，不增加逻辑表。
-- 所有FK默认NO ACTION：保留审核历史，不提供级联删除。
BEGIN;

CREATE TABLE ep_document (
  id uuid PRIMARY KEY,
  title text NOT NULL CHECK (length(btrim(title)) > 0),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ep_document_version (
  id uuid PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES ep_document(id),
  version_no integer NOT NULL CHECK (version_no > 0),
  original_object_key text NOT NULL,
  original_sha256 text NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
  ocr_object_key text,
  parser_config jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parser_config) = 'object'),
  parse_status text NOT NULL DEFAULT 'queued'
    CHECK (parse_status IN ('queued','running','succeeded','failed','cancelled')),
  page_count integer CHECK (page_count > 0),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, version_no),
  UNIQUE (document_id, id),
  CHECK (parse_status <> 'succeeded' OR (ocr_object_key IS NOT NULL AND page_count IS NOT NULL))
);
CREATE TABLE ep_source_chunk (
  id uuid PRIMARY KEY,
  document_version_id uuid NOT NULL REFERENCES ep_document_version(id),
  chapter_path text NOT NULL DEFAULT '',
  page_no integer NOT NULL CHECK (page_no > 0),
  chunk_no integer NOT NULL CHECK (chunk_no >= 0),
  text text NOT NULL,
  text_sha256 text NOT NULL CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
  locator jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(locator) = 'object'),
  quality_flags jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(quality_flags) = 'array'),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_version_id, page_no, chunk_no),
  UNIQUE (document_version_id, id)
);
CREATE TABLE ep_team (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK (length(btrim(name)) > 0),
  completed_build_id uuid,
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ep_build (
  id uuid PRIMARY KEY,
  team_id uuid NOT NULL REFERENCES ep_team(id),
  build_no integer NOT NULL CHECK (build_no > 0),
  phase text NOT NULL DEFAULT 'prelearning' CHECK (phase IN
    ('prelearning','summary','specialists','fallback','router','production_config','final_review')),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused','cancelled','completed')),
  current_artifact_id uuid,
  focus_revision_id uuid,
  waiting_question_message_id uuid,
  manifest_revision_id uuid,
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  message_seq bigint NOT NULL DEFAULT 0 CHECK (message_seq >= 0),
  event_seq bigint NOT NULL DEFAULT 0 CHECK (event_seq >= 0),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (team_id, build_no),
  UNIQUE (team_id, id),
  CHECK (status <> 'completed' OR (phase = 'final_review' AND manifest_revision_id IS NOT NULL))
);
CREATE TABLE ep_build_document (
  build_id uuid NOT NULL REFERENCES ep_build(id),
  document_id uuid NOT NULL,
  document_version_id uuid NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (build_id, document_id),
  UNIQUE (build_id, document_version_id),
  FOREIGN KEY (document_id, document_version_id) REFERENCES ep_document_version(document_id, id)
);
CREATE TABLE ep_artifact (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL REFERENCES ep_build(id),
  kind text NOT NULL CHECK (kind IN
    ('book_summary','learning_unit','agent','keyword_rule','qa_example','team_manifest')),
  agent_role text,
  logical_key text NOT NULL CHECK (length(btrim(logical_key)) > 0),
  sort_no integer NOT NULL DEFAULT 0 CHECK (sort_no >= 0),
  current_revision_id uuid,
  dependency_state text NOT NULL DEFAULT 'current' CHECK (dependency_state IN ('current','stale')),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (build_id, logical_key),
  UNIQUE (build_id, id),
  CHECK ((kind = 'agent' AND agent_role IS NOT NULL AND agent_role IN ('specialist','fallback','router'))
      OR (kind <> 'agent' AND agent_role IS NULL))
);
CREATE UNIQUE INDEX ep_artifact_single_fallback ON ep_artifact(build_id)
  WHERE kind = 'agent' AND agent_role = 'fallback';
CREATE UNIQUE INDEX ep_artifact_single_router ON ep_artifact(build_id)
  WHERE kind = 'agent' AND agent_role = 'router';
CREATE INDEX ep_artifact_order ON ep_artifact(build_id, kind, sort_no);
CREATE TABLE ep_artifact_revision (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL,
  artifact_id uuid NOT NULL,
  revision_no integer NOT NULL CHECK (revision_no > 0),
  based_on_revision_id uuid,
  schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
  body jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(body) = 'object'),
  system_prompt text,
  content_sha256 text CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  generation_status text NOT NULL DEFAULT 'queued' CHECK (generation_status IN
    ('queued','running','succeeded','failed','cancelled')),
  review_status text NOT NULL DEFAULT 'pending' CHECK (review_status IN
    ('pending','needs_reason','changes_requested','completed')),
  generator_config jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(generator_config) = 'object'),
  change_reason text,
  sealed_at timestamptz,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (artifact_id, revision_no),
  UNIQUE (artifact_id, id),
  UNIQUE (build_id, id),
  UNIQUE (build_id, id, content_sha256),
  FOREIGN KEY (build_id, artifact_id) REFERENCES ep_artifact(build_id, id),
  FOREIGN KEY (artifact_id, based_on_revision_id) REFERENCES ep_artifact_revision(artifact_id, id),
  CHECK (based_on_revision_id IS NULL OR based_on_revision_id <> id),
  CHECK ((sealed_at IS NULL AND content_sha256 IS NULL AND generation_status <> 'succeeded')
      OR (sealed_at IS NOT NULL AND content_sha256 IS NOT NULL AND generation_status = 'succeeded')),
  CHECK (review_status = 'pending' OR sealed_at IS NOT NULL)
);
CREATE TABLE ep_revision_source (
  build_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  document_version_id uuid NOT NULL,
  chunk_id uuid NOT NULL,
  start_offset integer NOT NULL CHECK (start_offset >= 0),
  end_offset integer NOT NULL CHECK (end_offset > start_offset),
  purpose text NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (revision_id, chunk_id, start_offset, end_offset),
  FOREIGN KEY (build_id, revision_id) REFERENCES ep_artifact_revision(build_id, id),
  FOREIGN KEY (build_id, document_version_id) REFERENCES ep_build_document(build_id, document_version_id),
  FOREIGN KEY (document_version_id, chunk_id) REFERENCES ep_source_chunk(document_version_id, id)
);
CREATE TABLE ep_revision_dependency (
  build_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  depends_on_revision_id uuid NOT NULL,
  relation text NOT NULL CHECK (relation IN ('derived_from','routes_to','includes')),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (revision_id, depends_on_revision_id, relation),
  FOREIGN KEY (build_id, revision_id) REFERENCES ep_artifact_revision(build_id, id),
  FOREIGN KEY (build_id, depends_on_revision_id) REFERENCES ep_artifact_revision(build_id, id),
  CHECK (revision_id <> depends_on_revision_id)
);
CREATE INDEX ep_dependency_reverse ON ep_revision_dependency(depends_on_revision_id);
CREATE TABLE ep_message (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL REFERENCES ep_build(id),
  seq bigint NOT NULL CHECK (seq > 0),
  role text NOT NULL CHECK (role IN ('admin','assistant','system')),
  actor_id text NOT NULL,
  client_request_id text,
  content text NOT NULL,
  reply_to_message_id uuid,
  context_snapshot jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(context_snapshot) = 'object'),
  interpretation jsonb CHECK (jsonb_typeof(interpretation) = 'object'),
  processing_status text NOT NULL DEFAULT 'queued' CHECK (processing_status IN
    ('queued','running','succeeded','failed','cancelled')),
  result jsonb CHECK (jsonb_typeof(result) = 'object'),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (build_id, seq),
  UNIQUE (build_id, client_request_id),
  UNIQUE (build_id, id),
  FOREIGN KEY (build_id, reply_to_message_id) REFERENCES ep_message(build_id, id),
  CHECK (role <> 'admin' OR (client_request_id IS NOT NULL AND length(client_request_id) > 0))
);
CREATE TABLE ep_review (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  scope text NOT NULL CHECK (scope IN
    ('summary.full','description','duty','model','tools','capabilities','boundaries','prompt.full','artifact.full','team.full')),
  decision text NOT NULL CHECK (decision IN ('approve','reject')),
  reason text,
  actor_id text NOT NULL,
  message_id uuid NOT NULL,
  action_no integer NOT NULL CHECK (action_no >= 0),
  presented_message_id uuid NOT NULL,
  reviewed_sha256 text NOT NULL CHECK (reviewed_sha256 ~ '^[0-9a-f]{64}$'),
  decided_at timestamptz NOT NULL DEFAULT now(),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (message_id, action_no),
  FOREIGN KEY (build_id, revision_id, reviewed_sha256)
    REFERENCES ep_artifact_revision(build_id, id, content_sha256),
  FOREIGN KEY (build_id, message_id) REFERENCES ep_message(build_id, id),
  FOREIGN KEY (build_id, presented_message_id) REFERENCES ep_message(build_id, id)
);
CREATE INDEX ep_review_history ON ep_review(revision_id, scope, decided_at);
CREATE TABLE ep_clarification (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL,
  artifact_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('rejection_reason','confirmation_scope','source_conflict','requirement')),
  question text NOT NULL,
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved')),
  answer text,
  opened_message_id uuid NOT NULL,
  resolved_message_id uuid,
  resolution_revision_id uuid,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (build_id, artifact_id) REFERENCES ep_artifact(build_id, id),
  FOREIGN KEY (artifact_id, revision_id) REFERENCES ep_artifact_revision(artifact_id, id),
  FOREIGN KEY (artifact_id, resolution_revision_id) REFERENCES ep_artifact_revision(artifact_id, id),
  FOREIGN KEY (build_id, opened_message_id) REFERENCES ep_message(build_id, id),
  FOREIGN KEY (build_id, resolved_message_id) REFERENCES ep_message(build_id, id),
  CHECK ((status = 'open' AND resolved_message_id IS NULL AND answer IS NULL AND resolution_revision_id IS NULL)
      OR (status = 'resolved' AND resolved_message_id IS NOT NULL AND answer IS NOT NULL AND length(btrim(answer)) > 0))
);
CREATE INDEX ep_clarification_pending ON ep_clarification(build_id, status);
CREATE TABLE ep_job (
  id uuid PRIMARY KEY,
  build_id uuid REFERENCES ep_build(id),
  document_version_id uuid REFERENCES ep_document_version(id),
  kind text NOT NULL CHECK (kind IN ('parse_document','prelearn','generate_summary','revise_summary','generate_artifact','revise_artifact','interpret_message')),
  target_revision_id uuid,
  expected_current_revision_id uuid,
  dedup_key text NOT NULL UNIQUE CHECK (length(dedup_key) > 0),
  status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','succeeded','failed','cancelled')),
  phase text NOT NULL DEFAULT 'waiting',
  input jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(input) = 'object'),
  checkpoint jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(checkpoint) = 'object'),
  external_refs jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(external_refs) = 'object'),
  lease_owner text,
  lease_until timestamptz,
  lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  next_run_at timestamptz NOT NULL DEFAULT now(),
  cancel_requested boolean NOT NULL DEFAULT false,
  error jsonb CHECK (jsonb_typeof(error) = 'object'),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (build_id, target_revision_id) REFERENCES ep_artifact_revision(build_id, id),
  FOREIGN KEY (build_id, expected_current_revision_id) REFERENCES ep_artifact_revision(build_id, id),
  FOREIGN KEY (build_id, document_version_id) REFERENCES ep_build_document(build_id, document_version_id),
  CHECK ((kind = 'parse_document' AND build_id IS NULL AND document_version_id IS NOT NULL
            AND target_revision_id IS NULL AND expected_current_revision_id IS NULL)
      OR (kind <> 'parse_document' AND build_id IS NOT NULL)),
  CHECK (kind NOT IN ('generate_summary','revise_summary','generate_artifact','revise_artifact') OR
    (target_revision_id IS NOT NULL AND expected_current_revision_id IS NOT NULL)),
  CHECK ((status = 'running' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
      OR (status <> 'running' AND lease_owner IS NULL AND lease_until IS NULL))
);
CREATE INDEX ep_job_ready ON ep_job(status, next_run_at) WHERE status = 'queued';
CREATE INDEX ep_job_expired ON ep_job(lease_until) WHERE status = 'running';
CREATE INDEX ep_job_build ON ep_job(build_id, status);
CREATE TABLE ep_event (
  id uuid PRIMARY KEY,
  build_id uuid NOT NULL REFERENCES ep_build(id),
  seq bigint NOT NULL CHECK (seq > 0),
  type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(payload) = 'object'),
  published_at timestamptz,
  publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
  next_publish_at timestamptz NOT NULL DEFAULT now(),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (build_id, seq)
);
CREATE INDEX ep_event_unpublished ON ep_event(next_publish_at) WHERE published_at IS NULL;

-- 循环关系的反向指针最后添加，所有引用保持本team/build/artifact范围。
ALTER TABLE ep_team ADD CONSTRAINT ep_team_completed_build_fk
  FOREIGN KEY (id, completed_build_id) REFERENCES ep_build(team_id, id);
ALTER TABLE ep_build ADD CONSTRAINT ep_build_current_artifact_fk
  FOREIGN KEY (id, current_artifact_id) REFERENCES ep_artifact(build_id, id);
ALTER TABLE ep_build ADD CONSTRAINT ep_build_focus_revision_fk
  FOREIGN KEY (id, focus_revision_id) REFERENCES ep_artifact_revision(build_id, id);
ALTER TABLE ep_build ADD CONSTRAINT ep_build_waiting_message_fk
  FOREIGN KEY (id, waiting_question_message_id) REFERENCES ep_message(build_id, id);
ALTER TABLE ep_build ADD CONSTRAINT ep_build_manifest_fk
  FOREIGN KEY (id, manifest_revision_id) REFERENCES ep_artifact_revision(build_id, id);
ALTER TABLE ep_artifact ADD CONSTRAINT ep_artifact_current_revision_fk
  FOREIGN KEY (id, current_revision_id) REFERENCES ep_artifact_revision(artifact_id, id);
COMMIT;

-- 服务事务必须实现（本DDL不假称已通过数据库强制）：
-- 1 身份/资料读取授权；按kind+schema_version验证完整body；hash按规范内容计算。
-- 2 原文及已封存body/prompt/hash/来源/依赖不可修改；管理员意见另存。
-- 3 持有build行锁校验当前对象/版本/hash/展示消息/明确确认范围及必要澄清，
--   同事务写review、状态、message.result及event；状态转换不是任意CHECK内跳转。
-- 4 依赖同构建已由FK保证；新增边的无环性、relation两端kind、
--   based_on的更早版本、offset<=Unicode码点数、物理页上界仍由服务验证。
-- 5 build.current_artifact是待办位置，focus_revision可指历史，不能用焦点代替待办。
-- 6 锁顺序team(若需要)->build->job->artifact/revision；lease_epoch+owner+有效期+
--   cancel_requested+expected_current_revision+目标及依赖同时通过才可写入工作结果。
-- 7 message_seq/event_seq必须在build锁内分配及提交，不用全局序列代替。
-- 8 completed_build对应completed且清单/必要成员审核完成；ProductionService最终确认事务实施。
-- 9 updated_at由服务更新；DDL不创建账户、权限策略、清理任务或新基础设施。
