package com.nexus.agent.service;

import com.nexus.agent.config.MemoryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "agent.memory", name = "provider", havingValue = "redis")
public class RedisSessionMemoryRepository implements SessionMemoryRepository {

    private final StringRedisTemplate redisTemplate;
    private final MemoryProperties properties;

    public RedisSessionMemoryRepository(StringRedisTemplate redisTemplate, MemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public String buildRecentContext(String sessionId) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        List<String> lines = redisTemplate.opsForList().range(key(sessionId), 0, -1);
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
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

        String key = key(sessionId);
        String line = role + ": " + text.trim();
        redisTemplate.opsForList().rightPush(key, line);

        int windowSize = Math.max(0, properties.getWindowSize());
        if (windowSize > 0) {
            redisTemplate.opsForList().trim(key, -windowSize, -1);
        } else {
            redisTemplate.delete(key);
            return;
        }

        long ttlSeconds = Math.max(60, properties.getTtlSeconds());
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    private String key(String sessionId) {
        return properties.getKeyPrefix() + sessionId;
    }
}
