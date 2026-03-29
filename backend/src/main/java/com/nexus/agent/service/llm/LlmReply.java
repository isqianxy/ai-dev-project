package com.nexus.agent.service.llm;

public record LlmReply(
        String provider,
        String model,
        String content
) {}
