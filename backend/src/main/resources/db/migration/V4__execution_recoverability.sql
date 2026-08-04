CREATE TABLE trading_accounts (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_trading_accounts_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT uq_trading_account_workspace_id UNIQUE (workspace_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_runs (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    frozen_version_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_execution_runs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_execution_runs_account FOREIGN KEY (workspace_id, account_id)
        REFERENCES trading_accounts(workspace_id, id),
    CONSTRAINT fk_execution_runs_version FOREIGN KEY (workspace_id, frozen_version_id)
        REFERENCES strategy_versions(workspace_id, id),
    CONSTRAINT uq_execution_workspace_key UNIQUE (workspace_id, idempotency_key),
    CONSTRAINT uq_execution_workspace_id UNIQUE (workspace_id, id),
    INDEX idx_execution_runs_workspace_status (workspace_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_audit_events (
    id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36),
    workspace_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(160),
    action VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_execution_audit_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_execution_audit_run FOREIGN KEY (workspace_id, run_id)
        REFERENCES execution_runs(workspace_id, id),
    INDEX idx_execution_audit_run_time (run_id, occurred_at),
    INDEX idx_execution_audit_workspace_time (workspace_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_orders (
    id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    broker_order_id VARCHAR(80),
    status VARCHAR(32) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_execution_orders_run FOREIGN KEY (run_id) REFERENCES execution_runs(id),
    CONSTRAINT uq_execution_order_key UNIQUE (run_id, idempotency_key),
    CONSTRAINT uq_execution_order_run_id UNIQUE (run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_trades (
    id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    trade_id VARCHAR(80) NOT NULL,
    order_id VARCHAR(36),
    quantity INT NOT NULL,
    price DECIMAL(20,8) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    traded_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_execution_trades_run FOREIGN KEY (run_id) REFERENCES execution_runs(id),
    CONSTRAINT fk_execution_trades_order FOREIGN KEY (run_id, order_id)
        REFERENCES execution_orders(run_id, id),
    CONSTRAINT uq_execution_trade_id UNIQUE (run_id, trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_positions (
    run_id VARCHAR(36) NOT NULL,
    instrument VARCHAR(40) NOT NULL,
    direction VARCHAR(12) NOT NULL,
    quantity INT NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id, instrument, direction),
    CONSTRAINT fk_execution_positions_run FOREIGN KEY (run_id) REFERENCES execution_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_balances (
    run_id VARCHAR(36) NOT NULL,
    currency VARCHAR(12) NOT NULL,
    available DECIMAL(20,8) NOT NULL,
    margin DECIMAL(20,8) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id, currency),
    CONSTRAINT fk_execution_balances_run FOREIGN KEY (run_id) REFERENCES execution_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
