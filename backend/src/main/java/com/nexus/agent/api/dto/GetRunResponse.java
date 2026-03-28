package com.nexus.agent.api.dto;

import java.time.Instant;

public record GetRunResponse(
        String runId,
        String sessionId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
