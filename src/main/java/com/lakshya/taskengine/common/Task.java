package com.lakshya.taskengine.common;

import java.time.OffsetDateTime;

public record Task(
        long id,
        String type,
        String payload,
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