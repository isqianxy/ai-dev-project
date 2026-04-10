package com.nexus.agent.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.kb.model.KbChunk;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Component
public class ChromaVectorStoreClient {
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final KbBuildProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final AtomicReference<String> cachedCollectionId = new AtomicReference<>();

    public ChromaVectorStoreClient(KbBuildProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public int upsert(List<KbChunk> chunks, List<float[]> vectors) {
        if (chunks.isEmpty()) {
            return 0;
        }
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("向量数量与 chunk 数量不一致");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>(chunks.size());
            List<String> documents = new ArrayList<>(chunks.size());
            List<Map<String, Object>> metadatas = new ArrayList<>(chunks.size());
            List<List<Float>> embeddings = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                KbChunk chunk = chunks.get(i);
                ids.add(chunk.chunkId());
                documents.add(chunk.content());
                metadatas.add(Map.of(
                        "kbId", chunk.kbId(),
                        "documentId", chunk.documentId(),
                        "sourceUri", chunk.sourceUri(),
                        "chunkIndex", chunk.chunkIndex()
                ));
                embeddings.add(toFloatList(vectors.get(i)));
            }
            payload.put("ids", ids);
            payload.put("documents", documents);
            payload.put("metadatas", metadatas);
            payload.put("embeddings", embeddings);

            String collectionId = getOrCreateCollectionId();
            String path = properties.getVector().getUpsertPathTemplate()
                    .formatted(collectionId);
            String url = trimSlash(properties.getVector().getEndpoint()) + path;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Chroma upsert 失败，状态码=" + response.statusCode() + ", body=" + response.body());
            }
            return chunks.size();
        } catch (Exception e) {
            throw new IllegalStateException("写入 Chroma 失败: " + e.getMessage(), e);
        }
    }

    private String getOrCreateCollectionId() {
        String cached = cachedCollectionId.get();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String configured = properties.getVector().getCollection();
        if (configured != null && UUID_PATTERN.matcher(configured).matches()) {
            cachedCollectionId.set(configured);
            return configured;
        }
        try {
            String url = trimSlash(properties.getVector().getEndpoint()) + "/api/v1/collections";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("查询 Chroma collection 失败，状态码=" + response.statusCode() + ", body=" + response.body());
            }
            String id = resolveCollectionIdFromList(response.body(), configured);
            if (id.isBlank()) {
                throw new IllegalStateException("未找到 collection=" + configured + "，请先创建 collection 再执行构建");
            }
            cachedCollectionId.set(id);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("获取 Chroma collection 失败: " + e.getMessage(), e);
        }
    }

    private String resolveCollectionIdFromList(String body, String configuredName) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null || !root.isArray()) {
                return "";
            }
            for (var node : root) {
                String name = node.path("name").asText("");
                if (configuredName != null && configuredName.equals(name)) {
                    return node.path("id").asText("");
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> out = new ArrayList<>(arr.length);
        for (float v : arr) {
            out.add(v);
        }
        return out;
    }
}
