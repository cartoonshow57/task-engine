package com.lakshya.taskengine.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateTaskRequest(
        @NotBlank String type,
        @Min(1) Integer maxAttempts
) {}