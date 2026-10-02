CREATE TABLE tasks (
    id               BIGSERIAL PRIMARY KEY,
    type             VARCHAR(100) NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    run_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by        VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_tasks_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','DEAD'))
);

CREATE INDEX idx_tasks_claimable ON tasks (run_at) WHERE status = 'PENDING';