package com.lakshya.taskengine.common;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonRawValue;

public record Task(
        long id,
        String type,
        @JsonRawValue String payload,
        TaskStatus status,
        int attempts,
        int maxAttempts,
        OffsetDateTime runAt,
        String lockedBy,
        OffsetDateTime leaseExpiresAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}