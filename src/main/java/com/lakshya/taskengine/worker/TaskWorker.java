package com.lakshya.taskengine.worker;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;

@Component
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final int LEASE_SECONDS = 30;

    private final TaskRepository repository;
    private final TaskHandler handler;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

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
        try {
            handler.handle(task);
        } catch (Exception e) {
            log.warn("Task {} failed on attempt {}: {}", task.id(), task.attempts(), e.toString());
            repository.markFailed(task.id(), workerId, e.toString());
            return;
        }
        repository.markSucceeded(task.id(), workerId);
    }
}