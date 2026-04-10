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
    private final Hybrid hybrid = new Hybrid();

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

    public Hybrid getHybrid() {
        return hybrid;
    }

    public static class Embedding {
        private String provider = "openai";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int timeoutMs = 15000;
        private int maxRetries = 1;
        private String teiPath = "/embed";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

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

        public String getTeiPath() {
            return teiPath;
        }

        public void setTeiPath(String teiPath) {
            this.teiPath = teiPath;
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

    public static class Hybrid {
        private String kbId = "default_kb";
        private int vectorTopK = 8;
        private int graphTopK = 8;
        private double vectorWeight = 0.7;
        private double graphWeight = 0.3;
        private final Query2Doc query2doc = new Query2Doc();
        private final Parallel parallel = new Parallel();
        private final Rerank rerank = new Rerank();
        private final Chroma chroma = new Chroma();
        private final Neo4j neo4j = new Neo4j();

        public String getKbId() {
            return kbId;
        }

        public void setKbId(String kbId) {
            this.kbId = kbId;
        }

        public int getVectorTopK() {
            return vectorTopK;
        }

        public void setVectorTopK(int vectorTopK) {
            this.vectorTopK = vectorTopK;
        }

        public int getGraphTopK() {
            return graphTopK;
        }

        public void setGraphTopK(int graphTopK) {
            this.graphTopK = graphTopK;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getGraphWeight() {
            return graphWeight;
        }

        public void setGraphWeight(double graphWeight) {
            this.graphWeight = graphWeight;
        }

        public Query2Doc getQuery2doc() {
            return query2doc;
        }

        public Parallel getParallel() {
            return parallel;
        }

        public Rerank getRerank() {
            return rerank;
        }

        public Chroma getChroma() {
            return chroma;
        }

        public Neo4j getNeo4j() {
            return neo4j;
        }
    }

    public static class Query2Doc {
        private boolean enabled = false;
        private int maxQueries = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxQueries() {
            return maxQueries;
        }

        public void setMaxQueries(int maxQueries) {
            this.maxQueries = maxQueries;
        }
    }

    public static class Parallel {
        private int threads = 6;
        private int taskTimeoutMs = 1800;
        private int routeTimeoutMs = 2500;

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public int getTaskTimeoutMs() {
            return taskTimeoutMs;
        }

        public void setTaskTimeoutMs(int taskTimeoutMs) {
            this.taskTimeoutMs = taskTimeoutMs;
        }

        public int getRouteTimeoutMs() {
            return routeTimeoutMs;
        }

        public void setRouteTimeoutMs(int routeTimeoutMs) {
            this.routeTimeoutMs = routeTimeoutMs;
        }
    }

    public static class Rerank {
        private boolean enabled = false;
        private String endpoint = "http://localhost:18081";
        private String path = "/rerank";
        private int timeoutMs = 1800;
        private int candidateTopK = 20;
        private double weight = 0.75;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getCandidateTopK() {
            return candidateTopK;
        }

        public void setCandidateTopK(int candidateTopK) {
            this.candidateTopK = candidateTopK;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }

    public static class Chroma {
        private String endpoint = "http://localhost:8000";
        private String collection = "nexus_kb_1024";
        private int timeoutMs = 1200;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Neo4j {
        private String endpoint = "http://localhost:7474";
        private String username = "neo4j";
        private String password = "";
        private String txPath = "/db/neo4j/tx/commit";
        private int maxDepth = 2;
        private int timeoutMs = 1200;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
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

        public String getTxPath() {
            return txPath;
        }

        public void setTxPath(String txPath) {
            this.txPath = txPath;
        }

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
