package com.nexus.agent.store;

import com.nexus.agent.domain.RunRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRunStore {

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();

    public RunRecord create(String sessionId, String prompt) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String normalizedPrompt = prompt == null ? "" : prompt;
        RunRecord r = new RunRecord(id, sessionId, normalizedPrompt, "PENDING", now, now);
        runs.put(id, r);
        return r;
    }

    public Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public void markStreaming(String runId) {
        runs.computeIfPresent(runId, (k, v) -> new RunRecord(v.runId(), v.sessionId(), v.prompt(), "STREAMING", v.createdAt(), Instant.now()));
    }

    public void markCompleted(String runId) {
        runs.computeIfPresent(runId, (k, v) -> new RunRecord(v.runId(), v.sessionId(), v.prompt(), "COMPLETED", v.createdAt(), Instant.now()));
    }

    public void markFailed(String runId) {
        runs.computeIfPresent(runId, (k, v) -> new RunRecord(v.runId(), v.sessionId(), v.prompt(), "FAILED", v.createdAt(), Instant.now()));
    }
}
