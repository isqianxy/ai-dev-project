package com.nexus.agent.api.dto;

import java.time.Instant;

public record GetApprovalResponse(
        String approvalId,
        String runId,
        String sessionId,
        String action,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedBy
) {}
