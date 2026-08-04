CREATE TABLE strategy_spec_snapshots (
    id VARCHAR(36) NOT NULL,
    draft_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    strategy_id VARCHAR(36) NOT NULL,
    version_number INT NOT NULL,
    canonical_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    frozen_by_member_id VARCHAR(36) NOT NULL,
    frozen_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_snapshots_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_snapshots_strategy_version
        FOREIGN KEY (workspace_id, strategy_id, version_number)
        REFERENCES strategy_versions(workspace_id, strategy_id, version_number),
    CONSTRAINT fk_snapshots_actor FOREIGN KEY (workspace_id, frozen_by_member_id)
        REFERENCES team_members(workspace_id, id),
    CONSTRAINT uq_snapshots_draft UNIQUE (draft_id),
    CONSTRAINT uq_snapshots_strategy_version UNIQUE (strategy_id, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_records (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    actor_member_id VARCHAR(36) NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_approvals_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_approvals_actor FOREIGN KEY (workspace_id, actor_member_id)
        REFERENCES team_members(workspace_id, id),
    INDEX idx_approvals_entity (workspace_id, entity_type, entity_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_audit_events (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(36),
    action VARCHAR(64) NOT NULL,
    detail_json TEXT,
    actor_member_id VARCHAR(36),
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_task_audit_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_task_audit_actor FOREIGN KEY (workspace_id, actor_member_id)
        REFERENCES team_members(workspace_id, id),
    INDEX idx_task_audit_entity (workspace_id, entity_type, entity_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
