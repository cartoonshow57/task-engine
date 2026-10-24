package com.lakshya.taskengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;
import com.lakshya.taskengine.common.TaskStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "taskengine.scheduling.enabled=false")
class TaskRepositoryTest {

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM tasks").update();
    }

    @Test
    void newTaskIsPendingWithDefaults() {
        long id = repository.insert("send-email", "{\"to\":\"a@example.com\"}", 3);

        Task task = repository.findById(id).orElseThrow();

        assertThat(task.type()).isEqualTo("send-email");
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.attempts()).isZero();
        assertThat(task.maxAttempts()).isEqualTo(3);
        assertThat(task.lockedBy()).isNull();
    }

    @Test
    void claimNextMarksTheTaskRunningForThatWorker() {
        long id = repository.insert("send-email", "{}", 3);

        Task claimed = repository.claimNext("worker-1", 30).orElseThrow();

        assertThat(claimed.id()).isEqualTo(id);
        assertThat(claimed.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.lockedBy()).isEqualTo("worker-1");
        assertThat(claimed.leaseExpiresAt()).isNotNull();
    }

    @Test
    void claimNextReturnsNothingWhenNoTaskIsDue() {
        assertThat(repository.claimNext("worker-1", 30)).isEmpty();
    }

    @Test
    void markSucceededFinishesTheTask() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", 30);

        repository.markSucceeded(id, "worker-1");

        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.lockedBy()).isNull();
    }

    @Test
    void anotherWorkerCannotFinishYourTask() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", 30);

        repository.markSucceeded(id, "worker-2");

        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.lockedBy()).isEqualTo("worker-1");
    }

    @Test
    void manyWorkersNeverClaimTheSameTask() throws Exception {
        int taskCount = 200;
        int workerCount = 8;
        for (int i = 0; i < taskCount; i++) {
            repository.insert("send-email", "{}", 3);
        }

        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();

        for (int w = 1; w <= workerCount; w++) {
            String workerId = "worker-" + w;
            results.add(pool.submit(() -> {
                startingGun.await();
                List<Long> mine = new ArrayList<>();
                Optional<Task> next;
                while ((next = repository.claimNext(workerId, 30)).isPresent()) {
                    mine.add(next.get().id());
                }
                return mine;
            }));
        }

        startingGun.countDown();

        List<Long> allClaimed = new ArrayList<>();
        for (Future<List<Long>> result : results) {
            allClaimed.addAll(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(allClaimed).hasSize(taskCount);
        assertThat(allClaimed).doesNotHaveDuplicates();
    }

    @Test
    void expiredLeaseReturnsTheTaskToPending() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", -1); // a lease that is already over

        int recovered = repository.recoverExpiredTasks();

        assertThat(recovered).isEqualTo(1);
        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.lockedBy()).isNull();
        assertThat(task.attempts()).isEqualTo(1);
    }

    @Test
    void aTaskWithNoAttemptsLeftBecomesDeadWhenItsLeaseExpires() {
        long id = repository.insert("send-email", "{}", 1);
        repository.claimNext("worker-1", -1); // attempts is now 1 of 1

        repository.recoverExpiredTasks();

        Task task = repository.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.DEAD);
        assertThat(task.lastError()).contains("Lease expired");
    }

    @Test
    void aTaskWithAValidLeaseIsLeftAlone() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", 30);

        int recovered = repository.recoverExpiredTasks();

        assertThat(recovered).isZero();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void onlyTheOwnerCanExtendALease() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", 30);

        assertThat(repository.extendLease(id, "worker-1", 30)).isTrue();
        assertThat(repository.extendLease(id, "worker-2", 30)).isFalse();
    }

    @Test
    void aHeartbeatSavesTheTaskFromRecovery() {
        long id = repository.insert("send-email", "{}", 3);
        repository.claimNext("worker-1", -1); // the lease has already run out
        repository.extendLease(id, "worker-1", 30); // but the worker is alive and says so

        assertThat(repository.recoverExpiredTasks()).isZero();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
    }
}