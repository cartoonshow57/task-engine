package com.lakshya.taskengine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "taskengine.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryJob.class);

    private final TaskRepository repository;

    public LeaseRecoveryJob(TaskRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5000)
    public void recover() {
        int recovered = repository.recoverExpiredTasks();
        if (recovered > 0) {
            log.warn("Recovered {} task(s) whose lease expired", recovered);
        }
    }
}