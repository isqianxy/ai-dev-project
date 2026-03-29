package com.nexus.agent.store;

import com.nexus.agent.domain.ApprovalRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryApprovalStore {

    private final Map<String, ApprovalRecord> approvals = new ConcurrentHashMap<>();
    private final Map<String, String> approvedToolByRunId = new ConcurrentHashMap<>();

    public ApprovalRecord create(String runId, String sessionId, String action) {
        String approvalId = UUID.randomUUID().toString();
        ApprovalRecord record = new ApprovalRecord(
                approvalId,
                runId,
                sessionId,
                action,
                "PENDING",
                Instant.now(),
                null,
                null
        );
        approvals.put(approvalId, record);
        return record;
    }

    public Optional<ApprovalRecord> find(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    public ApprovalRecord resolve(String approvalId, String decision, String resolvedBy) {
        ApprovalRecord resolved = approvals.computeIfPresent(
                approvalId,
                (k, v) -> new ApprovalRecord(
                        v.approvalId(),
                        v.runId(),
                        v.sessionId(),
                        v.action(),
                        decision,
                        v.createdAt(),
                        Instant.now(),
                        resolvedBy
                )
        );
        if (resolved != null && "APPROVED".equals(resolved.status()) && resolved.action() != null) {
            String prefix = "TOOL_EXECUTE:";
            if (resolved.action().startsWith(prefix) && resolved.action().length() > prefix.length()) {
                String toolName = resolved.action().substring(prefix.length()).trim();
                if (!toolName.isBlank()) {
                    approvedToolByRunId.put(resolved.runId(), toolName);
                }
            }
        }
        return resolved;
    }

    public boolean consumeApprovedTool(String runId, String toolName) {
        if (runId == null || toolName == null) {
            return false;
        }
        String approvedTool = approvedToolByRunId.get(runId);
        if (!toolName.equals(approvedTool)) {
            return false;
        }
        return approvedToolByRunId.remove(runId, toolName);
    }
}
