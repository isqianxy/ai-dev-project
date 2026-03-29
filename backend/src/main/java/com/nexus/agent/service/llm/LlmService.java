package com.nexus.agent.service.llm;

import com.nexus.agent.config.LlmProperties;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LlmService {

    private final LlmProperties properties;
    private final List<LlmClient> clients;

    public LlmService(LlmProperties properties, List<LlmClient> clients) {
        this.properties = properties;
        this.clients = clients;
    }

    public LlmReply generate(String prompt) {
        String provider = properties.getProvider();
        for (LlmClient client : clients) {
            if (client.supports(provider)) {
                return client.generate(prompt);
            }
        }
        throw new IllegalStateException("未找到可用的 LLM client: " + provider);
    }

    public String currentProvider() {
        return properties.getProvider();
    }
}
