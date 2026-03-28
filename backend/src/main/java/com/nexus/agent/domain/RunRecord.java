package com.nexus.agent.domain;

import java.time.Instant;

public record RunRecord(
        String runId,
        String sessionId,
        String prompt,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
