package com.nexus.agent.service.llm;

import com.nexus.agent.config.LlmProperties;
import com.nexus.agent.config.StabilityProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LlmService {

    private final LlmProperties properties;
    private final StabilityProperties stabilityProperties;
    private final List<LlmClient> clients;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntilMs = new AtomicLong(0);

    public LlmService(LlmProperties properties, StabilityProperties stabilityProperties, List<LlmClient> clients) {
        this.properties = properties;
        this.stabilityProperties = stabilityProperties;
        this.clients = clients;
    }

    public LlmReply generate(String prompt) {
        checkCircuitState();
        String provider = properties.getProvider();
        for (LlmClient client : clients) {
            if (client.supports(provider)) {
                try {
                    LlmReply reply = client.generate(prompt);
                    consecutiveFailures.set(0);
                    circuitOpenUntilMs.set(0);
                    return reply;
                } catch (RuntimeException e) {
                    onFailure(e);
                    throw e;
                }
            }
        }
        throw new IllegalStateException("未找到可用的 LLM client: " + provider);
    }

    public String currentProvider() {
        return properties.getProvider();
    }

    private void checkCircuitState() {
        if (!stabilityProperties.isCircuitBreakerEnabled()) {
            return;
        }
        long openUntil = circuitOpenUntilMs.get();
        long now = System.currentTimeMillis();
        if (openUntil > now) {
            long waitMs = openUntil - now;
            throw new IllegalStateException("CIRCUIT_OPEN: 模型熔断中，请 " + waitMs + "ms 后重试");
        }
    }

    private void onFailure(RuntimeException source) {
        if (!stabilityProperties.isCircuitBreakerEnabled()) {
            return;
        }
        int threshold = Math.max(1, stabilityProperties.getCircuitFailureThreshold());
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= threshold) {
            long openMs = Math.max(1000, stabilityProperties.getCircuitOpenMs());
            circuitOpenUntilMs.set(System.currentTimeMillis() + openMs);
            throw new IllegalStateException(
                    "CIRCUIT_OPEN: 模型连续失败 " + failures + " 次，熔断 " + openMs + "ms；原始错误: " + source.getMessage(),
                    source
            );
        }
    }
}
