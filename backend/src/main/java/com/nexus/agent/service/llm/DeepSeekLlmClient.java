package com.nexus.agent.service.llm;

import com.nexus.agent.config.LlmProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class DeepSeekLlmClient implements LlmClient {

    private final LlmProperties properties;
    private ChatModel chatModel;

    public DeepSeekLlmClient(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && "deepseek".equals(provider.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public LlmReply generate(String prompt) {
        validateConfig();

        String userPrompt = prompt == null ? "" : prompt;
        int attempts = Math.max(0, properties.getMaxRetries()) + 1;
        RuntimeException lastException = null;

        for (int i = 1; i <= attempts; i++) {
            try {
                String answer = model().chat(userPrompt);
                if (answer == null || answer.isBlank()) {
                    throw new IllegalStateException("DeepSeek 响应为空");
                }
                return new LlmReply("deepseek", properties.getModel(), answer);
            } catch (Exception e) {
                lastException = new RuntimeException("调用 DeepSeek 失败（第 " + i + " 次）: " + e.getMessage(), e);
                if (i < attempts) {
                    sleep((long) i * 300L);
                }
            }
        }

        throw lastException == null ? new RuntimeException("调用 DeepSeek 失败") : lastException;
    }

    private ChatModel model() {
        if (chatModel == null) {
            chatModel = OpenAiChatModel.builder()
                    .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                    .apiKey(properties.getApiKey())
                    .modelName(properties.getModel())
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
        return chatModel;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    private void validateConfig() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.llm.base-url 未配置");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("agent.llm.api-key 未配置");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException("agent.llm.model 未配置");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
