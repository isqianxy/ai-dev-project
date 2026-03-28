package com.nexus.agent.api.dto;

public record ResolveApprovalRequest(
        String decision,
        String resolvedBy
) {}
