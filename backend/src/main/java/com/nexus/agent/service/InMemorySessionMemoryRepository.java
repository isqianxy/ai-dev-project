package com.nexus.agent.service;

import com.nexus.agent.config.MemoryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "agent.memory", name = "provider", havingValue = "in_memory", matchIfMissing = true)
public class InMemorySessionMemoryRepository implements SessionMemoryRepository {

    private final MemoryProperties properties;
    private final Map<String, Deque<MemoryItem>> memoryBySession = new ConcurrentHashMap<>();

    public InMemorySessionMemoryRepository(MemoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public String buildRecentContext(String sessionId) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        Deque<MemoryItem> items = memoryBySession.get(sessionId);
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : items) {
            sb.append(item.role()).append(": ").append(item.text()).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public void appendUserMessage(String sessionId, String text) {
        append(sessionId, "USER", text);
    }

    @Override
    public void appendAssistantMessage(String sessionId, String text) {
        append(sessionId, "ASSISTANT", text);
    }

    private void append(String sessionId, String role, String text) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        Deque<MemoryItem> queue = memoryBySession.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        queue.addLast(new MemoryItem(role, text.trim()));
        int windowSize = Math.max(0, properties.getWindowSize());
        while (queue.size() > windowSize) {
            queue.removeFirst();
        }
    }

    private record MemoryItem(String role, String text) {}
}
