package com.nexus.agent.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kb.build")
public class KbBuildProperties {

    private boolean enabled = false;
    private boolean exitAfterRun = true;
    private String kbId = "default_kb";
    private String input = "docs/kb/*.md";
    private int chunkSize = 600;
    private int chunkOverlap = 80;
    private final Embedding embedding = new Embedding();
    private final Vector vector = new Vector();
    private final Graph graph = new Graph();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExitAfterRun() {
        return exitAfterRun;
    }

    public void setExitAfterRun(boolean exitAfterRun) {
        this.exitAfterRun = exitAfterRun;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Vector getVector() {
        return vector;
    }

    public Graph getGraph() {
        return graph;
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

    public static class Vector {
        private String endpoint = "http://localhost:8000";
        private String collection = "nexus_kb";
        private String upsertPathTemplate = "/api/v1/collections/%s/upsert";

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

        public String getUpsertPathTemplate() {
            return upsertPathTemplate;
        }

        public void setUpsertPathTemplate(String upsertPathTemplate) {
            this.upsertPathTemplate = upsertPathTemplate;
        }
    }

    public static class Graph {
        private String endpoint = "http://localhost:7474";
        private String username = "neo4j";
        private String password = "";
        private String txPath = "/db/neo4j/tx/commit";
        private int maxTriplesPerChunk = 20;

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

        public int getMaxTriplesPerChunk() {
            return maxTriplesPerChunk;
        }

        public void setMaxTriplesPerChunk(int maxTriplesPerChunk) {
            this.maxTriplesPerChunk = maxTriplesPerChunk;
        }
    }
}
