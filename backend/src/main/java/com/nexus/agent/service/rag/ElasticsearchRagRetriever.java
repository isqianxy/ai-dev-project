package com.nexus.agent.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.config.RagProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M10a 占位实现：
 * 先完成 provider 路由与上下文注入闭环，ES 检索细节在后续 M10b/M10c 迭代落地。
 */
@Component
@ConditionalOnProperty(prefix = "agent.rag", name = "provider", havingValue = "elastic")
public class ElasticsearchRagRetriever implements RagRetriever {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ElasticsearchRagRetriever(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String providerName() {
        return "elastic";
    }

    @Override
    public List<Content> retrieve(Query query) {
        RagProperties.Elastic elastic = ragProperties.getElastic();
        if (elastic.getBaseUrl() == null || elastic.getBaseUrl().isBlank()) {
            return List.of();
        }
        try {
            String q = query == null || query.text() == null ? "" : query.text();
            int topK = Math.max(1, ragProperties.getTopK());
            String body = buildSearchBody(q, topK);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(elastic.getBaseUrl()) + "/" + elastic.getIndex() + "/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyAuth(builder, elastic);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseContents(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String buildSearchBody(String query, int topK) {
        return """
                {
                  "size": %d,
                  "query": {
                    "multi_match": {
                      "query": %s,
                      "fields": ["content^2", "title"]
                    }
                  }
                }
                """.formatted(topK, toJsonString(query));
    }

    private List<Content> parseContents(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray()) {
                return List.of();
            }
            java.util.ArrayList<Content> list = new java.util.ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode sourceNode = hit.path("_source");
                String source = sourceNode.path("source_uri").asText("es://unknown");
                String content = sourceNode.path("content").asText("");
                double score = hit.path("_score").asDouble(0.0);
                Metadata metadata = new Metadata()
                        .put("source", source)
                        .put("score", score);
                list.add(Content.from(TextSegment.from(content, metadata), scoreMetadata(score)));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<dev.langchain4j.rag.content.ContentMetadata, Object> scoreMetadata(double score) {
        Map<dev.langchain4j.rag.content.ContentMetadata, Object> m = new LinkedHashMap<>();
        m.put(dev.langchain4j.rag.content.ContentMetadata.SCORE, score);
        return m;
    }

    private static void applyAuth(HttpRequest.Builder builder, RagProperties.Elastic elastic) {
        if (elastic.getApiKey() != null && !elastic.getApiKey().isBlank()) {
            builder.header("Authorization", "ApiKey " + elastic.getApiKey().trim());
            return;
        }
        if (elastic.getUsername() != null && !elastic.getUsername().isBlank()) {
            String raw = elastic.getUsername() + ":" + (elastic.getPassword() == null ? "" : elastic.getPassword());
            String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String toJsonString(String text) {
        if (text == null) {
            return "\"\"";
        }
        String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }
}
