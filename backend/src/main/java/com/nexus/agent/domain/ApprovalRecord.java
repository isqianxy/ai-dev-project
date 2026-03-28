package com.nexus.agent.domain;

import java.time.Instant;

public record ApprovalRecord(
        String approvalId,
        String runId,
        String sessionId,
        String action,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedBy
) {}
