package com.lakshya.taskengine.common;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "taskengine.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class QueueDepthMetrics {

    private final TaskRepository repository;
    private final Map<TaskStatus, AtomicLong> depth = new EnumMap<>(TaskStatus.class);

    public QueueDepthMetrics(TaskRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (TaskStatus status : TaskStatus.values()) {
            AtomicLong value = new AtomicLong();
            depth.put(status, value);
            Gauge.builder("taskengine.tasks", value, v -> v.get())
                    .tag("status", status.name().toLowerCase())
                    .description("Number of tasks in each status")
                    .register(registry);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void refresh() {
        Map<TaskStatus, Long> counts = repository.countByStatus();
        depth.forEach((status, value) -> value.set(counts.getOrDefault(status, 0L)));
    }
}