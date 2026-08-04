CREATE TABLE strategies (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_version INT NOT NULL,
    created_by_member_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_strategies_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_strategies_creator FOREIGN KEY (workspace_id, created_by_member_id)
        REFERENCES team_members(workspace_id, id),
    CONSTRAINT uq_strategies_name UNIQUE (workspace_id, normalized_name),
    CONSTRAINT uq_strategies_workspace_id UNIQUE (workspace_id, id),
    INDEX idx_strategies_workspace_status (workspace_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE strategy_versions (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    strategy_id VARCHAR(36) NOT NULL,
    version_number INT NOT NULL,
    content_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_by_member_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_strategy_versions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_strategy_versions_strategy FOREIGN KEY (workspace_id, strategy_id)
        REFERENCES strategies(workspace_id, id),
    CONSTRAINT fk_strategy_versions_creator FOREIGN KEY (workspace_id, created_by_member_id)
        REFERENCES team_members(workspace_id, id),
    CONSTRAINT uq_strategy_versions_number UNIQUE (strategy_id, version_number),
    CONSTRAINT uq_strategy_versions_workspace_id UNIQUE (workspace_id, id),
    CONSTRAINT uq_strategy_versions_workspace_number
        UNIQUE (workspace_id, strategy_id, version_number),
    INDEX idx_strategy_versions_strategy_created (strategy_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
