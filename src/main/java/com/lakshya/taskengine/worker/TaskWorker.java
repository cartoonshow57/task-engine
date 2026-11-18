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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.EngineMetrics;
import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;

import io.micrometer.core.instrument.Timer;

@Component
@ConditionalOnProperty(name = "taskengine.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final int DEFAULT_LEASE_SECONDS = 30;
    private static final long DEFAULT_HEARTBEAT_MILLIS = 10_000;

    private final TaskRepository repository;
    private final TaskHandler handler;
    private final EngineMetrics metrics;
    private final int leaseSeconds;
    private final long heartbeatMillis;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public TaskWorker(TaskRepository repository, TaskHandler handler, EngineMetrics metrics) {
        this(repository, handler, metrics, DEFAULT_LEASE_SECONDS, DEFAULT_HEARTBEAT_MILLIS);
    }

    public TaskWorker(TaskRepository repository, TaskHandler handler, EngineMetrics metrics,
                      int leaseSeconds, long heartbeatMillis) {
        this.repository = repository;
        this.handler = handler;
        this.metrics = metrics;
        this.leaseSeconds = leaseSeconds;
        this.heartbeatMillis = heartbeatMillis;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        Optional<Task> next;
        while ((next = repository.claimNext(workerId, leaseSeconds)).isPresent()) {
            run(next.get());
        }
    }

    private void run(Task task) {
        log.info("{} claimed task {} (attempt {})", workerId, task.id(), task.attempts());
        metrics.taskClaimed();
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> beat(task, finished), heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        Timer.Sample sample = metrics.startTimer();
        Exception failure = null;
        try {
            handler.handle(task);
        } catch (Exception e) {
            failure = e;
        } finally {
            finished.set(true);
            heartbeat.cancel(false);
            metrics.stopTimer(sample);
        }

        if (failure == null) {
            if (repository.markSucceeded(task.id(), workerId)) {
                metrics.taskSucceeded();
            } else {
                metrics.resultLost();
                log.warn("Task {} finished, but its result was not recorded: this worker no longer owns it", task.id());
            }
        } else {
            log.warn("Task {} failed on attempt {}: {}", task.id(), task.attempts(), failure.toString());
            if (repository.markFailed(task.id(), workerId, failure.toString())) {
                metrics.taskFailed();
            } else {
                metrics.resultLost();
                log.warn("Task {} failed, but the failure was not recorded: this worker no longer owns it", task.id());
            }
        }
    }

    private void beat(Task task, AtomicBoolean finished) {
        try {
            boolean stillMine = repository.extendLease(task.id(), workerId, leaseSeconds);
            if (!stillMine && !finished.get()) {
                log.warn("Lost ownership of task {}", task.id());
            }
        } catch (Exception e) {
            log.warn("Heartbeat for task {} failed: {}", task.id(), e.toString());
        }
    }
}