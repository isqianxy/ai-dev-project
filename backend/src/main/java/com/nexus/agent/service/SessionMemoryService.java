package com.nexus.agent.service;

import com.nexus.agent.config.MemoryProperties;
import org.springframework.stereotype.Service;

@Service
public class SessionMemoryService {

    private final MemoryProperties properties;
    private final SessionMemoryRepository repository;

    public SessionMemoryService(MemoryProperties properties, SessionMemoryRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public String buildRecentContext(String sessionId) {
        if (!properties.isEnabled()) {
            return "";
        }
        return repository.buildRecentContext(sessionId);
    }

    public void appendUserMessage(String sessionId, String text) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.appendUserMessage(sessionId, text);
    }

    public void appendAssistantMessage(String sessionId, String text) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.appendAssistantMessage(sessionId, text);
    }
}
