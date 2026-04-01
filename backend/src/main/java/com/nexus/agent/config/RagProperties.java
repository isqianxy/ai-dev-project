package com.nexus.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent.rag")
public class RagProperties {

    private boolean enabled = true;
    private String provider = "mock";
    private int topK = 3;
    private List<String> localDocuments = new ArrayList<>(List.of(
            "Nexus Agent 支持 Session / Run / SSE 事件流，便于追踪每次执行。",
            "工具系统走 LangChain4j function calling，支持风险分级与审批。",
            "记忆层支持 in_memory 与 redis 切换，默认窗口大小为 6。",
            "RAG 结果会在 THINK 前注入上下文，并通过 rag.retrieved 事件可观测。",
            "系统在模型连续失败时启用熔断，快速失败并保护主链路。"
    ));
    private final Embedding embedding = new Embedding();
    private final Elastic elastic = new Elastic();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public List<String> getLocalDocuments() {
        return localDocuments;
    }

    public void setLocalDocuments(List<String> localDocuments) {
        this.localDocuments = localDocuments;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Elastic getElastic() {
        return elastic;
    }

    public static class Embedding {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int timeoutMs = 15000;
        private int maxRetries = 1;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class Elastic {
        private String baseUrl = "";
        private String index = "agent_knowledge_chunks";
        private String apiKey = "";
        private String username = "";
        private String password = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
