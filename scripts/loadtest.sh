#!/usr/bin/env bash
# Load test: empties the tasks table, queues noop tasks, and times how long they take to finish.
# Usage: scripts/loadtest.sh [task_count]   (default 10000)
# WARNING: this deletes every task in the database.
set -euo pipefail

COUNT=${1:-10000}
PSQL="docker compose exec -T postgres psql -U taskengine -tA"

$PSQL -c "TRUNCATE tasks RESTART IDENTITY;"
$PSQL -c "INSERT INTO tasks (type, max_attempts) SELECT 'noop', 3 FROM generate_series(1, $COUNT);"
echo "Queued $COUNT noop tasks. Waiting for the workers to finish them..."

while true; do
  REMAINING=$($PSQL -c "SELECT count(*) FROM tasks WHERE status <> 'SUCCEEDED';")
  [ "$REMAINING" -eq 0 ] && break
  sleep 2
done

$PSQL -c "SELECT count(*) || ' tasks in ' || (max(updated_at) - min(updated_at)) || ' = ' || round(count(*) / extract(epoch FROM max(updated_at) - min(updated_at))) || ' tasks per second' FROM tasks WHERE type = 'noop';"