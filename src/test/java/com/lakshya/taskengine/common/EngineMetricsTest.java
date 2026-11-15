package com.lakshya.taskengine.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EngineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);

    @Test
    void countersGoUpByOneEachTime() {
        metrics.taskClaimed();
        metrics.taskClaimed();
        metrics.taskSucceeded();
        metrics.taskFailed();
        metrics.resultLost();

        assertThat(registry.counter("taskengine.tasks.claimed").count()).isEqualTo(2.0);
        assertThat(registry.counter("taskengine.tasks.succeeded").count()).isEqualTo(1.0);
        assertThat(registry.counter("taskengine.tasks.failed").count()).isEqualTo(1.0);
        assertThat(registry.counter("taskengine.tasks.lost.results").count()).isEqualTo(1.0);
    }

    @Test
    void recoveredAddsTheNumberOfReleasedTasks() {
        metrics.tasksRecovered(3);
        metrics.tasksRecovered(2);

        assertThat(registry.counter("taskengine.tasks.recovered").count()).isEqualTo(5.0);
    }

    @Test
    void timerRecordsEachRun() {
        Timer.Sample sample = metrics.startTimer();
        metrics.stopTimer(sample);

        assertThat(registry.timer("taskengine.handler.time").count()).isEqualTo(1);
    }
}