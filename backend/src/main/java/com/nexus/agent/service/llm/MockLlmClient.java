package com.nexus.agent.service.llm;

import org.springframework.stereotype.Component;

@Component
public class MockLlmClient implements LlmClient {

    @Override
    public boolean supports(String provider) {
        return provider == null || provider.isBlank() || "mock".equalsIgnoreCase(provider);
    }

    @Override
    public LlmReply generate(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        String answer = normalized.isEmpty()
                ? "这是 Mock 模型回复：你没有提供问题。"
                : "这是 Mock 模型回复：" + normalized;
        return new LlmReply("mock", "mock-model", answer);
    }
}
