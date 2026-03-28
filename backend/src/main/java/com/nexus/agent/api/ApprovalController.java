package com.nexus.agent.api;

import com.nexus.agent.api.dto.GetApprovalResponse;
import com.nexus.agent.api.dto.ResolveApprovalRequest;
import com.nexus.agent.domain.ApprovalRecord;
import com.nexus.agent.exception.BadRequestException;
import com.nexus.agent.exception.NotFoundException;
import com.nexus.agent.store.InMemoryApprovalStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final InMemoryApprovalStore approvalStore;

    public ApprovalController(InMemoryApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @GetMapping("/{approvalId}")
    public GetApprovalResponse getApproval(@PathVariable String approvalId) {
        ApprovalRecord record = approvalStore.find(approvalId)
                .orElseThrow(() -> new NotFoundException("APPROVAL_NOT_FOUND", "审批不存在: " + approvalId));
        return toResponse(record);
    }

    @PostMapping("/{approvalId}/resolve")
    public GetApprovalResponse resolveApproval(
            @PathVariable String approvalId,
            @RequestBody ResolveApprovalRequest body
    ) {
        if (body == null || body.decision() == null || body.decision().isBlank()) {
            throw new BadRequestException("INVALID_DECISION", "decision 不能为空，允许值: APPROVED/REJECTED");
        }

        String decision = body.decision().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BadRequestException("INVALID_DECISION", "decision 仅允许 APPROVED 或 REJECTED");
        }

        ApprovalRecord exists = approvalStore.find(approvalId)
                .orElseThrow(() -> new NotFoundException("APPROVAL_NOT_FOUND", "审批不存在: " + approvalId));
        if (!"PENDING".equals(exists.status())) {
            throw new BadRequestException("APPROVAL_ALREADY_RESOLVED", "审批已处理，不能重复确认");
        }

        String resolvedBy = body.resolvedBy() == null || body.resolvedBy().isBlank()
                ? "system"
                : body.resolvedBy().trim();
        ApprovalRecord resolved = approvalStore.resolve(approvalId, decision, resolvedBy);
        return toResponse(resolved);
    }

    private static GetApprovalResponse toResponse(ApprovalRecord record) {
        return new GetApprovalResponse(
                record.approvalId(),
                record.runId(),
                record.sessionId(),
                record.action(),
                record.status(),
                record.createdAt(),
                record.resolvedAt(),
                record.resolvedBy()
        );
    }
}
