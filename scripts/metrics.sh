#!/usr/bin/env bash
# Prints the engine's main meters from a running app. Usage: scripts/metrics.sh [port]
BASE=localhost:${1:-8080}/actuator/metrics

value() {
  curl -s "$1" | grep -o '"value":[0-9.E+-]*' | head -1 | cut -d: -f2
}

echo "claimed         $(value "$BASE/taskengine.tasks.claimed")"
echo "succeeded       $(value "$BASE/taskengine.tasks.succeeded")"
echo "failed          $(value "$BASE/taskengine.tasks.failed")"
echo "lost results    $(value "$BASE/taskengine.tasks.lost.results")"
echo "recovered       $(value "$BASE/taskengine.tasks.recovered")"
echo "queue pending   $(value "$BASE/taskengine.tasks?tag=status:pending")"
echo "queue running   $(value "$BASE/taskengine.tasks?tag=status:running")"