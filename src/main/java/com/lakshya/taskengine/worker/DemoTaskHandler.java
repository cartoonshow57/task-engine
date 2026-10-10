package com.lakshya.taskengine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lakshya.taskengine.common.Task;

@Component
public class DemoTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoTaskHandler.class);

    @Override
    public void handle(Task task) throws Exception {
        switch (task.type()) {
            case "fail" -> throw new IllegalStateException("This task was designed to fail");
            case "slow" -> {
                log.info("Task {} started a slow job (20 seconds)", task.id());
                Thread.sleep(20_000);
            }
            default -> Thread.sleep(1000);
        }
        log.info("Finished task {} of type {}", task.id(), task.type());
    }
}