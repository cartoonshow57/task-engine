package com.lakshya.taskengine.common;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class EngineMetrics {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter lostResults;
    private final Counter recovered;
    private final Timer handlerTime;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = Counter.builder("taskengine.tasks.claimed")
                .description("Tasks claimed by this app's worker").register(registry);
        this.succeeded = Counter.builder("taskengine.tasks.succeeded")
                .description("Tasks this worker finished successfully").register(registry);
        this.failed = Counter.builder("taskengine.tasks.failed")
                .description("Failed attempts this worker recorded").register(registry);
        this.lostResults = Counter.builder("taskengine.tasks.lost.results")
                .description("Results not recorded because the worker no longer owned the task").register(registry);
        this.recovered = Counter.builder("taskengine.tasks.recovered")
                .description("Tasks released because their lease expired").register(registry);
        this.handlerTime = Timer.builder("taskengine.handler.time")
                .description("How long the task handler takes to run a task").register(registry);
    }

    public void taskClaimed() {
        claimed.increment();
    }

    public void taskSucceeded() {
        succeeded.increment();
    }

    public void taskFailed() {
        failed.increment();
    }

    public void resultLost() {
        lostResults.increment();
    }

    public void tasksRecovered(int count) {
        recovered.increment(count);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(handlerTime);
    }
}