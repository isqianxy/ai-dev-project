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

    public RunRecord create(String sessionId) {
        String id = UUID.randomUUID().toString();
        RunRecord r = new RunRecord(id, sessionId, "PENDING", Instant.now());
        runs.put(id, r);
        return r;
    }

    public Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public void markStreaming(String runId) {
        runs.computeIfPresent(runId, (k, v) -> new RunRecord(v.runId(), v.sessionId(), "STREAMING", v.createdAt()));
    }

    public void markCompleted(String runId) {
        runs.computeIfPresent(runId, (k, v) -> new RunRecord(v.runId(), v.sessionId(), "COMPLETED", v.createdAt()));
    }
}
