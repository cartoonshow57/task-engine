package com.lakshya.taskengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.lakshya.taskengine.common.EngineMetrics;
import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;
import com.lakshya.taskengine.common.TaskStatus;
import com.lakshya.taskengine.worker.TaskHandler;
import com.lakshya.taskengine.worker.TaskWorker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "taskengine.scheduling.enabled=false")
class TaskWorkerTest {

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcClient jdbc;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM tasks").update();
    }

    private TaskWorker worker(TaskHandler handler) {
        return new TaskWorker(repository, handler, metrics);
    }

    private double count(String meter) {
        return registry.counter(meter).count();
    }

    @Test
    void aSuccessfulHandlerFinishesTheTask() {
        long id = repository.insert("send-email", "{}", 3);

        worker(task -> { }).poll();

        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.attempts()).isEqualTo(1);
        assertThat(count("taskengine.tasks.claimed")).isEqualTo(1.0);
        assertThat(count("taskengine.tasks.succeeded")).isEqualTo(1.0);
    }

    @Test
    void aFailingHandlerSendsTheTaskBackForARetry() {
        long id = repository.insert("fail", "{}", 3);

        worker(task -> { throw new IllegalStateException("boom"); }).poll();

        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.lastError()).contains("boom");
        assertThat(task.runAt()).isAfter(OffsetDateTime.now());
        assertThat(count("taskengine.tasks.failed")).isEqualTo(1.0);
        assertThat(count("taskengine.tasks.succeeded")).isZero();
    }

    @Test
    void aTaskWithNoAttemptsLeftEndsUpDead() {
        long id = repository.insert("fail", "{}", 1);

        worker(task -> { throw new IllegalStateException("boom"); }).poll();

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.DEAD);
    }

    @Test
    void oneCallToPollWorksThroughEveryDueTask() {
        for (int i = 0; i < 5; i++) {
            repository.insert("send-email", "{}", 3);
        }
        AtomicInteger calls = new AtomicInteger();

        worker(task -> calls.incrementAndGet()).poll();

        assertThat(calls.get()).isEqualTo(5);
        assertThat(repository.countByStatus()).containsEntry(TaskStatus.SUCCEEDED, 5L);
        assertThat(count("taskengine.tasks.claimed")).isEqualTo(5.0);
    }

    @Test
    void aTaskThatIsNotDueYetIsLeftAlone() {
        repository.insert("later", "{}", 3, 60, null);
        AtomicInteger calls = new AtomicInteger();

        worker(task -> calls.incrementAndGet()).poll();

        assertThat(calls.get()).isZero();
        assertThat(repository.countByStatus()).containsEntry(TaskStatus.PENDING, 1L);
    }

    @Test
    void aWorkerThatLostItsTaskCountsALostResultAndDoesNotOverwriteIt() {
        long id = repository.insert("send-email", "{}", 3);
        TaskHandler handlerThatLosesTheTask = task -> jdbc.sql("""
                UPDATE tasks
                SET status = 'PENDING', locked_by = NULL, lease_expires_at = NULL,
                    run_at = now() + interval '1 hour'
                WHERE id = :id
                """).param("id", task.id()).update();

        worker(handlerThatLosesTheTask).poll();

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(count("taskengine.tasks.lost.results")).isEqualTo(1.0);
        assertThat(count("taskengine.tasks.succeeded")).isZero();
    }

    @Test
    void theHeartbeatKeepsALongTaskFromBeingRecovered() {
        long id = repository.insert("slow", "{}", 3);
        AtomicInteger recoveredDuringTheRun = new AtomicInteger(-1);
        TaskHandler slow = task -> {
            Thread.sleep(3000); // longer than the 2-second lease
            recoveredDuringTheRun.set(repository.recoverExpiredTasks());
        };

        new TaskWorker(repository, slow, metrics, 2, 400).poll();

        assertThat(recoveredDuringTheRun.get()).isZero();
        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.attempts()).isEqualTo(1);
    }
}