# Task Engine

![CI](https://github.com/cartoonshow57/task-engine/actions/workflows/ci.yml/badge.svg)

A distributed task execution engine in Java. Clients submit tasks over a REST API, and workers claim them from PostgreSQL, run them, and handle failures: retries with exponential backoff, a dead state for tasks that keep failing, and automatic recovery of tasks whose worker crashed or stalled. Several copies of the app can run at the same time against one database.

Built with Java 25, Spring Boot, PostgreSQL, Flyway, and Testcontainers.

## Features

- **REST API** to submit (`POST /tasks`) and inspect (`GET /tasks/{id}`) tasks, with JSON payloads and request validation.
- **PostgreSQL as the queue.** Workers claim tasks with `SELECT ... FOR UPDATE SKIP LOCKED`, so several workers never take the same task.
- **Retries with exponential backoff** (2s, 4s, 8s, ...), and a `DEAD` state once a task has used all its attempts.
- **Leases and heartbeats.** A claimed task is leased to one worker, and the worker keeps extending the lease while it runs. If the worker dies, the lease expires and a recovery job puts the task back in the queue.
- **Ownership checks.** A worker that has lost its task cannot overwrite the new owner's result, and it logs a warning.
- **Tested.** Automated tests run against a real PostgreSQL in Docker, including eight workers racing for 200 tasks, and the build runs on every push with GitHub Actions.
- **Idempotency keys.** A client can send an `Idempotency-Key` header, so retrying a request after a timeout returns the original task instead of creating a duplicate. A unique index in PostgreSQL enforces this, even when retries arrive at the same instant.


## Architecture

```mermaid
flowchart LR
    Client([Client])

    subgraph App["Task Engine app (run as many copies as you like)"]
        API[TaskController]
        Worker[TaskWorker]
        Handler[TaskHandler]
        Heartbeat[Heartbeat timer]
        Recovery[LeaseRecoveryJob]
        Repo[TaskRepository]
    end

    DB[("PostgreSQL<br/>tasks table")]

    Client -->|POST and GET /tasks| API
    API --> Repo
    Worker -->|claim, record result| Repo
    Worker -->|run task| Handler
    Worker -.->|starts while a task runs| Heartbeat
    Heartbeat -->|extend lease| Repo
    Recovery -->|release expired leases| Repo
    Repo --> DB
```

Every copy of the app runs the same four parts: the API accepts tasks, the worker runs them, a heartbeat timer keeps the lease on a running task alive, and a recovery job releases tasks whose lease expired. All coordination goes through PostgreSQL, so the copies never talk to each other, and adding capacity means starting another copy.

## Running it

**You need:** Java 25 and Docker (with Compose). Maven isn't needed, because the project includes the Maven wrapper.

```bash
git clone https://github.com/cartoonshow57/task-engine.git
cd task-engine
docker compose up -d        # starts PostgreSQL
./mvnw spring-boot:run      # starts the app on port 8080
```

Submit a task and look at it:

```bash
curl -X POST localhost:8080/tasks -H "Content-Type: application/json" \
  -d '{"type":"send-email","payload":{"to":"a@example.com"}}'
# {"id":1}

curl localhost:8080/tasks/1
```

The included demo handler treats every task type as a one-second job, with two exceptions: `slow` takes 20 seconds, and `fail` always fails. They're handy for trying out the failure handling below. Real task types would be added by implementing the `TaskHandler` interface.

**Run several workers** by building the jar once and starting it more than once, each copy on its own port:

```bash
./mvnw -q -DskipTests package
java -jar target/task-engine-0.0.1-SNAPSHOT.jar
java -jar target/task-engine-0.0.1-SNAPSHOT.jar --server.port=8081
```

**Run the tests** (Docker must be running, because the tests start their own PostgreSQL):

```bash
./mvnw test
```


## API

| Request | Body | Response |
|---|---|---|
| `POST /tasks` | `type` (required), `maxAttempts` (optional, at least 1, default 3), `payload` (optional JSON object). Optional header `Idempotency-Key` (up to 200 characters). | `201` with `{"id": 1}` for a new task. `200` with the original id if the same key and request were already seen. `422` if the key was used for a different request. `400` with the reason if the request is invalid. |
| `GET /tasks/{id}` | none | `200` with the task, including its status, attempts, and payload. `404` if there is no such task. |
| `GET /actuator/health` | none | `200` with a status of `UP` while the app is healthy. |

## How a task moves through the system

| Status | Meaning |
|---|---|
| `PENDING` | Waiting to be claimed. It is either new, or waiting for its retry time. |
| `RUNNING` | Claimed by a worker, which holds a lease on it. |
| `SUCCEEDED` | Finished. |
| `DEAD` | Used all its attempts without succeeding. The last error is kept in `last_error`. |

A worker claims the oldest due `PENDING` task, which becomes `RUNNING`. When the handler finishes, the task becomes `SUCCEEDED`. If the handler throws, the task goes back to `PENDING` with a later `run_at`, or becomes `DEAD` if no attempts are left.

## How failures are handled

| What goes wrong | What the engine does | Tests that prove it |
|---|---|---|
| A handler throws an error | The task goes back to `PENDING`, and its next try is delayed by 2s, then 4s, then 8s. The error message is saved. | `aFailedTaskGoesBackToPendingWithALaterRunTime`, `aFailedTaskWaitsForItsRetryTimeBeforeBeingClaimedAgain` |
| A task keeps failing | After `maxAttempts` tries it becomes `DEAD` instead of retrying forever. | `aTaskBecomesDeadAfterItsLastFailedAttempt` |
| Several workers look for work at once | The claim is a single SQL statement using `FOR UPDATE SKIP LOCKED`, so each task goes to exactly one worker. | `manyWorkersNeverClaimTheSameTask` (8 workers, 200 tasks) |
| A worker crashes in the middle of a task | The task's lease runs out. A recovery job, which runs every 5 seconds, puts the task back in the queue. The crash costs the task one attempt, so a task that keeps killing workers ends up `DEAD`. | `expiredLeaseReturnsTheTaskToPending`, `aTaskWithNoAttemptsLeftBecomesDeadWhenItsLeaseExpires` |
| A worker is slow but still alive | While a task runs, a heartbeat keeps extending its lease, so the recovery job leaves it alone. | `aHeartbeatSavesTheTaskFromRecovery`, `aTaskWithAValidLeaseIsLeftAlone` |
| A worker reports on a task it no longer owns | Every update checks that the task is still `RUNNING` and still owned by that worker. If not, nothing changes and the worker logs a warning. | `anotherWorkerCannotFinishYourTask`, `anotherWorkerCannotRecordYourFailure`, `onlyTheOwnerCanExtendALease` |
| A client sends an invalid request | The API answers `400` and says which field was wrong and why. | `emptyTypeIsRejectedAndNamesTheField`, `maxAttemptsBelowOneIsRejected`, `payloadThatIsNotAnObjectIsRejected` |
| A client retries a request, for example after a timeout | With an `Idempotency-Key` header, the engine returns the original task instead of creating a second one. A unique index makes this hold even when retries arrive at the same instant. | `sameKeyTwiceCreatesOnlyOneTask`, `manyIdenticalRequestsAtTheSameInstantCreateOneTask`, `repeatedKeyReturnsTheSameTaskWith200`, `reusedKeyWithADifferentRequestIsRejectedWith422` |

I also checked these cases by hand. I killed the app with `kill -9` in the middle of a task and restarted it. The recovery job put the task back, and it finished on its second attempt. With a 10-second lease and a 20-second task, the task ran three times and ended `DEAD` before the heartbeat existed. With the heartbeat, the same task ran once and succeeded. With two copies of the app working through six slow tasks, each task was claimed exactly once, in about half the time one copy needs.

### Delivery guarantee

The engine delivers **at least once**. If a worker freezes after doing the work but before recording it, the lease expires and the task runs again. Handlers should therefore be safe to repeat. Almost all task systems make this trade, because losing a task is usually worse than running it twice. Exactly-once execution cannot be guaranteed when a task has effects outside the database, such as sending an email. Idempotency keys protect against duplicate submissions, but they don't change this guarantee: a task that is claimed twice, for example after a worker freezes, still runs twice.

## Design decisions

- **PostgreSQL as the queue.** It is one dependency, and the claim and the status change happen in one transaction. `FOR UPDATE SKIP LOCKED` is built for this pattern. The cost is that workers poll, and that queue traffic shares a database with the rest of the data.
- **Leases, not long locks.** The database row lock is held only for the milliseconds a claim takes. While a task runs, ownership is recorded in the row itself (`locked_by`, `lease_expires_at`), and the lease says when to give up on a worker.
- **A heartbeat on its own thread.** The worker thread is busy inside the handler, so a separate timer extends the lease. It stops before the result is recorded, and a late beat after the task has finished is ignored.
- **One SQL statement for each state change.** Claiming, finishing, failing, extending a lease, and recovering all happen in a single `UPDATE` with a guard in its `WHERE` clause. There is no gap between "check" and "change" for another worker to slip into.
- **At least once, not exactly once.** See the delivery guarantee above.
- **JSON payloads stored as `jsonb`.** PostgreSQL rejects anything that is not valid JSON, and the API only accepts a JSON object.
- **Idempotency through a unique index.** The key is a header, because it identifies the request and isn't part of the task's data. The check is `INSERT ... ON CONFLICT DO NOTHING` against a unique index, so PostgreSQL decides the winner when identical requests collide. A repeat gets `200` and the original id, a reused key with a different request gets `422`, and tasks without a key are unaffected.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `taskengine.scheduling.enabled` | `true` | When `false`, the worker and the recovery job are not created. The tests use this, and it also gives an API-only copy of the app. |
| `server.port` | `8080` | The port of the web server. Use a different one for each copy. |
| `spring.datasource.*` | local Docker PostgreSQL | The database connection (see `application.properties`). |
| `management.endpoints.web.exposure.include` | `health,metrics` | Which Actuator endpoints are reachable over HTTP. |


## Performance

Measured with `scripts/loadtest.sh` on a laptop, with the app and PostgreSQL (in Docker) on the same machine. The script empties the `tasks` table, queues 10,000 `noop` tasks (the handler does nothing, so the test measures the engine itself), waits for all of them to finish, and divides by the time between the first and last completion. **It deletes every task in the database**, so use it only on a development database.

| Workers | Commit mode | Tasks per second |
|---|---|---|
| 1 | PostgreSQL default | 49 to 51 (two runs) |
| 2 | PostgreSQL default | 53 |
| 4 | PostgreSQL default | 95 |
| 1 | `synchronous_commit = off` (diagnosis only) | 1,092 |

What the numbers show:

- Each task takes two commits, one to claim it and one to record the result, and by default each commit waits for PostgreSQL to write it safely to disk. That wait is most of the cost: turning it off made one worker about 22 times faster.
- With the default setting, more workers help because their waits overlap. Four workers were about 1.9 times as fast as one. The two-worker run was only 8% faster than one worker and was not repeated.
- A two-worker run that started right after a large `DELETE` managed 33 tasks per second, against 53 from a clean start, which suggests that leftover row versions slowed it down. The script uses `TRUNCATE` so every run starts clean.
- `synchronous_commit = off` can lose the most recent commits if PostgreSQL crashes, so it was used only to find the bottleneck. With at-least-once delivery, losing the record that a task finished would only make it run again, but losing a freshly submitted task would lose work.

These are laptop numbers for tasks that do nothing. They show how the engine behaves, not what a production deployment would reach.


## Metrics

The app reports metrics through Spring Boot Actuator and Micrometer at `/actuator/metrics`. Spring provides the JVM, database connection pool, and web request metrics. The engine adds its own:

| Metric | Kind | What it shows |
|---|---|---|
| `taskengine.tasks.claimed` | counter | Tasks this app's worker claimed |
| `taskengine.tasks.succeeded` | counter | Tasks it finished successfully |
| `taskengine.tasks.failed` | counter | Failed attempts it recorded |
| `taskengine.tasks.lost.results` | counter | Results not recorded because the worker no longer owned the task |
| `taskengine.tasks.recovered` | counter | Tasks released after their lease expired |
| `taskengine.handler.time` | timer | How long the handler takes to run a task |
| `taskengine.tasks` (tag `status`) | gauge | Tasks currently in each status: `pending`, `running`, `succeeded`, `dead`. Refreshed every 5 seconds |

The counters and the timer count only what one copy of the app does, so with several copies a monitoring system adds them up. The `taskengine.tasks` gauge reads the shared database, so every copy reports the same totals. `scripts/metrics.sh [port]` prints the main meters of a running copy.

In a load test of 3,000 `noop` tasks, the handler timer recorded 0.024 seconds in total, about 8 microseconds per task. At the measured rate of about 50 tasks per second, each task takes around 20 milliseconds from claim to result, so almost all of the engine's time goes on database round trips. This matches the commit-wait finding in the Performance section.


## Project layout

```
src/main/java/com/lakshya/taskengine/
  api/      TaskController, CreateTaskRequest, ApiExceptionHandler
  common/   Task, TaskStatus, TaskRepository, EngineMetrics, QueueDepthMetrics
  worker/   TaskWorker, TaskHandler, DemoTaskHandler, LeaseRecoveryJob
src/main/resources/db/migration/
            V1__create_tasks_table.sql, V2__add_idempotency_key.sql
src/test/java/com/lakshya/taskengine/
            TaskRepositoryTest, TaskApiTest, TestcontainersConfiguration
            common/EngineMetricsTest
.github/workflows/ci.yml
docker-compose.yml
scripts/loadtest.sh
scripts/metrics.sh
```

## Known limitations and next steps

- **Idempotency keys never expire.** A key stays with its task for as long as the task is stored, and there is no cleanup yet.
- **Metrics are only available through the Actuator endpoint.** There is no Prometheus export, dashboard, or alerting yet.
- **No authentication.** The API and the Actuator endpoints are open, which is fine on a laptop but not on a server.
- - **The worker loop has no automated tests of its own.** The repository, the HTTP endpoints, and the metrics are tested. The worker and the recovery job are covered only through the repository methods they call.
- **Finished tasks stay in the table.** At scale they would need archiving or partitioning.
- **Retries have no jitter.** At scale, tasks that fail together would retry together.
- **Polling.** Workers ask for work once a second. At larger scale, I would look at `LISTEN`/`NOTIFY` or a message broker, and at separate queues for different task types.