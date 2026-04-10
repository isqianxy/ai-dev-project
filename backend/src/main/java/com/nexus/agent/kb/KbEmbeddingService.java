package com.nexus.agent.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.kb.model.KbChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class KbEmbeddingService {

    private static final int FALLBACK_DIM = 128;
    private final KbBuildProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public KbEmbeddingService(KbBuildProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<float[]> embed(List<KbChunk> chunks) {
        List<float[]> remote = embedWithConfiguredProvider(chunks);
        if (!remote.isEmpty() && remote.size() == chunks.size()) {
            return remote;
        }
        return embedWithHash(chunks);
    }

    private List<float[]> embedWithConfiguredProvider(List<KbChunk> chunks) {
        String provider = properties.getEmbedding().getProvider();
        if (provider == null || provider.isBlank() || "openai".equalsIgnoreCase(provider)) {
            return embedWithOpenAiCompatible(chunks);
        }
        if ("tei".equalsIgnoreCase(provider)) {
            return embedWithTei(chunks);
        }
        return List.of();
    }

    private List<float[]> embedWithOpenAiCompatible(List<KbChunk> chunks) {
        KbBuildProperties.Embedding embedding = properties.getEmbedding();
        if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()
                || embedding.getApiKey() == null || embedding.getApiKey().isBlank()) {
            return List.of();
        }
        try {
            OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                    .baseUrl(trimSlash(embedding.getBaseUrl()))
                    .apiKey(embedding.getApiKey())
                    .modelName(embedding.getModel())
                    .timeout(Duration.ofMillis(Math.max(1000, embedding.getTimeoutMs())))
                    .maxRetries(Math.max(0, embedding.getMaxRetries()))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            List<TextSegment> all = chunks.stream().map(KbChunk::content).map(TextSegment::from).toList();
            Response<List<Embedding>> response = model.embedAll(all);
            if (response == null || response.content() == null || response.content().isEmpty()) {
                return List.of();
            }
            return response.content().stream().map(Embedding::vector).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<float[]> embedWithTei(List<KbChunk> chunks) {
        KbBuildProperties.Embedding embedding = properties.getEmbedding();
        if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()) {
            return List.of();
        }
        try {
            List<String> inputs = chunks.stream().map(KbChunk::content).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", inputs);
            payload.put("normalize", true);

            String teiPath = embedding.getTeiPath();
            if (teiPath == null || teiPath.isBlank()) {
                teiPath = "/embed";
            }
            String url = trimSlash(embedding.getBaseUrl()) + (teiPath.startsWith("/") ? teiPath : "/" + teiPath);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(1000, embedding.getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseTeiVectors(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<float[]> parseTeiVectors(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isArray()) {
                List<float[]> vectors = new ArrayList<>();
                for (JsonNode vectorNode : root) {
                    vectors.add(toFloatArray(vectorNode));
                }
                return vectors;
            }
            // 兼容部分网关返回 OpenAI 风格数据
            JsonNode dataNode = root.path("data");
            if (dataNode.isArray()) {
                List<float[]> vectors = new ArrayList<>();
                for (JsonNode item : dataNode) {
                    vectors.add(toFloatArray(item.path("embedding")));
                }
                return vectors;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static float[] toFloatArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new float[0];
        }
        List<Float> list = new ArrayList<>();
        node.forEach(n -> list.add((float) n.asDouble()));
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static List<float[]> embedWithHash(List<KbChunk> chunks) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            vectors.add(hashEmbedding(chunk.content()));
        }
        return vectors;
    }

    private static float[] hashEmbedding(String text) {
        float[] vec = new float[FALLBACK_DIM];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            int idx = Math.floorMod(normalized.charAt(i) * 31 + i, FALLBACK_DIM);
            vec[idx] += 1.0f;
        }
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(Math.max(norm, 1e-9));
        for (int i = 0; i < vec.length; i++) {
            vec[i] = vec[i] / norm;
        }
        return vec;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
