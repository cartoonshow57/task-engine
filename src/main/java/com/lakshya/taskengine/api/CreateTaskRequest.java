package com.lakshya.taskengine.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

public record CreateTaskRequest(
        @NotBlank String type,
        @Min(1) Integer maxAttempts,
        JsonNode payload,
        @Min(0) @Max(2_592_000) Integer delaySeconds
) {}