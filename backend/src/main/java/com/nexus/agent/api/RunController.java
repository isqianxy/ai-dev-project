package com.nexus.agent.api;

import com.nexus.agent.api.dto.CreateApprovalRequest;
import com.nexus.agent.api.dto.CreateApprovalResponse;
import com.nexus.agent.api.dto.CreateRunResponse;
import com.nexus.agent.api.dto.GetRunResponse;
import com.nexus.agent.domain.ApprovalRecord;
import com.nexus.agent.domain.RunRecord;
import com.nexus.agent.exception.NotFoundException;
import com.nexus.agent.service.MockRunEventService;
import com.nexus.agent.store.InMemoryApprovalStore;
import com.nexus.agent.store.InMemoryRunStore;
import com.nexus.agent.store.InMemorySessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class RunController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final InMemorySessionStore sessionStore;
    private final InMemoryRunStore runStore;
    private final InMemoryApprovalStore approvalStore;
    private final MockRunEventService mockRunEventService;

    public RunController(
            InMemorySessionStore sessionStore,
            InMemoryRunStore runStore,
            InMemoryApprovalStore approvalStore,
            MockRunEventService mockRunEventService
    ) {
        this.sessionStore = sessionStore;
        this.runStore = runStore;
        this.approvalStore = approvalStore;
        this.mockRunEventService = mockRunEventService;
    }

    @PostMapping("/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRunResponse createRun(@PathVariable String sessionId) {
        sessionStore.find(sessionId)
                .orElseThrow(() -> new NotFoundException("SESSION_NOT_FOUND", "会话不存在: " + sessionId));
        RunRecord run = runStore.create(sessionId);
        return new CreateRunResponse(run.runId(), sessionId, run.status(), run.createdAt(), run.updatedAt());
    }

    @GetMapping("/runs/{runId}")
    public GetRunResponse getRun(@PathVariable String runId) {
        RunRecord run = runStore.find(runId)
                .orElseThrow(() -> new NotFoundException("RUN_NOT_FOUND", "运行不存在: " + runId));
        return new GetRunResponse(run.runId(), run.sessionId(), run.status(), run.createdAt(), run.updatedAt());
    }

    @PostMapping("/runs/{runId}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateApprovalResponse createApproval(
            @PathVariable String runId,
            @RequestBody(required = false) CreateApprovalRequest body
    ) {
        RunRecord run = runStore.find(runId)
                .orElseThrow(() -> new NotFoundException("RUN_NOT_FOUND", "运行不存在: " + runId));

        String action = body != null && body.action() != null && !body.action().isBlank()
                ? body.action()
                : "WRITE_OPERATION";
        ApprovalRecord record = approvalStore.create(run.runId(), run.sessionId(), action);
        return new CreateApprovalResponse(
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

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(@PathVariable String runId) {
        RunRecord run = runStore.find(runId)
                .orElseThrow(() -> new NotFoundException("RUN_NOT_FOUND", "运行不存在: " + runId));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        mockRunEventService.streamMockEvents(emitter, run.runId(), run.sessionId());
        return emitter;
    }
}
