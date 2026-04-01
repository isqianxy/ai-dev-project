package com.nexus.agent.service.llm;

import com.nexus.agent.config.LlmProperties;
import com.nexus.agent.config.StabilityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmServiceCircuitBreakerTest {

    @Test
    void shouldOpenCircuitAfterThresholdFailures() {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setProvider("mock");

        StabilityProperties stability = new StabilityProperties();
        stability.setCircuitBreakerEnabled(true);
        stability.setCircuitFailureThreshold(2);
        stability.setCircuitOpenMs(5000);

        LlmClient failingClient = new LlmClient() {
            @Override
            public boolean supports(String provider) {
                return "mock".equals(provider);
            }

            @Override
            public LlmReply generate(String prompt) {
                throw new RuntimeException("boom");
            }
        };

        LlmService service = new LlmService(llmProperties, stability, List.of(failingClient));

        assertThatThrownBy(() -> service.generate("1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        assertThatThrownBy(() -> service.generate("2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CIRCUIT_OPEN:");

        assertThatThrownBy(() -> service.generate("3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CIRCUIT_OPEN:");
    }

    @Test
    void shouldResetCircuitAfterSuccess() {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setProvider("mock");

        StabilityProperties stability = new StabilityProperties();
        stability.setCircuitBreakerEnabled(true);
        stability.setCircuitFailureThreshold(3);
        stability.setCircuitOpenMs(2000);

        LlmClient flakyClient = new LlmClient() {
            private int count = 0;

            @Override
            public boolean supports(String provider) {
                return "mock".equals(provider);
            }

            @Override
            public LlmReply generate(String prompt) {
                count++;
                if (count == 1) {
                    throw new RuntimeException("first fail");
                }
                return new LlmReply("mock", "m", "ok");
            }
        };

        LlmService service = new LlmService(llmProperties, stability, List.of(flakyClient));

        assertThatThrownBy(() -> service.generate("x")).isInstanceOf(RuntimeException.class);
        LlmReply reply = service.generate("y");
        assertThat(reply.content()).isEqualTo("ok");
    }
}
