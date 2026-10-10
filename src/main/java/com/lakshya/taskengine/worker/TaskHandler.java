package com.lakshya.taskengine.worker;

import com.lakshya.taskengine.common.Task;

public interface TaskHandler {
    void handle(Task task) throws Exception;
}