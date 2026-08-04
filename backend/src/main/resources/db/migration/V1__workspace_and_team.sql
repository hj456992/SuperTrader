CREATE TABLE workspaces (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workspaces_normalized_name UNIQUE (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_members (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    normalized_display_name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_team_members_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT uq_team_members_name UNIQUE (workspace_id, normalized_display_name),
    CONSTRAINT uq_team_members_workspace_id UNIQUE (workspace_id, id),
    INDEX idx_team_members_workspace_active (workspace_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace_current_actors (
    workspace_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (workspace_id),
    CONSTRAINT fk_current_actor_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_current_actor_member FOREIGN KEY (workspace_id, member_id)
        REFERENCES team_members(workspace_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
