package com.nexus.agent.service;

public interface SessionMemoryRepository {

    String buildRecentContext(String sessionId);

    void appendUserMessage(String sessionId, String text);

    void appendAssistantMessage(String sessionId, String text);
}
