package com.nexus.agent.store;

import com.nexus.agent.domain.SessionRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore {

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    public SessionRecord create() {
        String id = UUID.randomUUID().toString();
        SessionRecord s = new SessionRecord(id, Instant.now());
        sessions.put(id, s);
        return s;
    }

    public Optional<SessionRecord> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
