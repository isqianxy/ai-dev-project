package com.nexus.agent.api;

import com.nexus.agent.api.dto.CreateRunResponse;
import com.nexus.agent.domain.RunRecord;
import com.nexus.agent.exception.NotFoundException;
import com.nexus.agent.service.MockRunEventService;
import com.nexus.agent.store.InMemoryRunStore;
import com.nexus.agent.store.InMemorySessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final MockRunEventService mockRunEventService;

    public RunController(
            InMemorySessionStore sessionStore,
            InMemoryRunStore runStore,
            MockRunEventService mockRunEventService
    ) {
        this.sessionStore = sessionStore;
        this.runStore = runStore;
        this.mockRunEventService = mockRunEventService;
    }

    @PostMapping("/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRunResponse createRun(@PathVariable String sessionId) {
        sessionStore.find(sessionId)
                .orElseThrow(() -> new NotFoundException("SESSION_NOT_FOUND", "会话不存在: " + sessionId));
        RunRecord run = runStore.create(sessionId);
        return new CreateRunResponse(run.runId(), sessionId, run.status());
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
