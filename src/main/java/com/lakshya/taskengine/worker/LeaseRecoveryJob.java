package com.lakshya.taskengine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.EngineMetrics;
import com.lakshya.taskengine.common.TaskRepository;

@Component
@ConditionalOnProperty(name = "taskengine.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryJob.class);

    private final TaskRepository repository;
    private final EngineMetrics metrics;

    public LeaseRecoveryJob(TaskRepository repository, EngineMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 5000)
    public void recover() {
        int recovered = repository.recoverExpiredTasks();
        if (recovered > 0) {
            metrics.tasksRecovered(recovered);
            log.warn("Recovered {} task(s) whose lease expired", recovered);
        }
    }
}