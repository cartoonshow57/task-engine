package com.lakshya.taskengine.worker;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "taskengine.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final int LEASE_SECONDS = 30;
    private static final int HEARTBEAT_SECONDS = 10;

    private final TaskRepository repository;
    private final TaskHandler handler;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });

    public TaskWorker(TaskRepository repository, TaskHandler handler) {
        this.repository = repository;
        this.handler = handler;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        Optional<Task> next;
        while ((next = repository.claimNext(workerId, LEASE_SECONDS)).isPresent()) {
            run(next.get());
        }
    }

    private void run(Task task) {
        log.info("{} claimed task {} (attempt {})", workerId, task.id(), task.attempts());
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> beat(task, finished), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Exception failure = null;
        try {
            handler.handle(task);
        } catch (Exception e) {
            failure = e;
        } finally {
            finished.set(true);
            heartbeat.cancel(false);
        }

        if (failure == null) {
            repository.markSucceeded(task.id(), workerId);
        } else {
            log.warn("Task {} failed on attempt {}: {}", task.id(), task.attempts(), failure.toString());
            repository.markFailed(task.id(), workerId, failure.toString());
        }
    }

    private void beat(Task task, AtomicBoolean finished) {
        try {
            boolean stillMine = repository.extendLease(task.id(), workerId, LEASE_SECONDS);
            if (!stillMine && !finished.get()) {
                log.warn("Lost ownership of task {}", task.id());
            }
        } catch (Exception e) {
            log.warn("Heartbeat for task {} failed: {}", task.id(), e.toString());
        }
    }
}