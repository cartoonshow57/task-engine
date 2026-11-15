ALTER TABLE tasks ADD COLUMN idempotency_key VARCHAR(200);

CREATE UNIQUE INDEX uq_tasks_idempotency_key ON tasks (idempotency_key);