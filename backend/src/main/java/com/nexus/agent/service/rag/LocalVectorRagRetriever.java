package com.nexus.agent.service.rag;

import com.nexus.agent.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "agent.rag", name = "provider", havingValue = "local_vector")
public class LocalVectorRagRetriever implements RagRetriever {

    private static final int FALLBACK_DIM = 128;
    private final RagProperties ragProperties;

    public LocalVectorRagRetriever(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public String providerName() {
        return "local_vector";
    }

    @Override
    public List<Content> retrieve(Query query) {
        String q = query == null || query.text() == null ? "" : query.text().trim();
        if (q.isBlank()) {
            return List.of();
        }
        List<String> docs = ragProperties.getLocalDocuments() == null
                ? List.of()
                : ragProperties.getLocalDocuments().stream()
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (docs.isEmpty()) {
            return List.of();
        }

        List<ScoredDoc> scored = scoreDocuments(q, docs);
        int topK = Math.max(1, ragProperties.getTopK());
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(topK)
                .map(this::toContent)
                .collect(Collectors.toList());
    }

    private List<ScoredDoc> scoreDocuments(String query, List<String> docs) {
        List<float[]> vectors = embedWithRemoteModel(query, docs);
        if (vectors.isEmpty() || vectors.size() != docs.size() + 1) {
            return scoreWithLocalHash(query, docs);
        }
        float[] qv = vectors.get(vectors.size() - 1);
        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            float[] dv = vectors.get(i);
            scored.add(new ScoredDoc("kb://local/doc-" + (i + 1), docs.get(i), cosine(dv, qv)));
        }
        return scored;
    }

    private List<float[]> embedWithRemoteModel(String query, List<String> docs) {
        RagProperties.Embedding embedding = ragProperties.getEmbedding();
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
            List<TextSegment> all = new ArrayList<>();
            docs.forEach(d -> all.add(TextSegment.from(d)));
            all.add(TextSegment.from(query));
            Response<List<Embedding>> response = model.embedAll(all);
            if (response == null || response.content() == null || response.content().isEmpty()) {
                return List.of();
            }
            return response.content().stream().map(Embedding::vector).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ScoredDoc> scoreWithLocalHash(String query, List<String> docs) {
        float[] qv = hashEmbedding(query);
        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            String doc = docs.get(i);
            float[] dv = hashEmbedding(doc);
            scored.add(new ScoredDoc("kb://local/doc-" + (i + 1), doc, cosine(dv, qv)));
        }
        return scored;
    }

    private Content toContent(ScoredDoc doc) {
        Metadata metadata = new Metadata()
                .put("source", doc.source())
                .put("score", doc.score());
        return Content.from(TextSegment.from(doc.content(), metadata), scoreMetadata(doc.score()));
    }

    private static java.util.Map<dev.langchain4j.rag.content.ContentMetadata, Object> scoreMetadata(double score) {
        java.util.Map<dev.langchain4j.rag.content.ContentMetadata, Object> m = new java.util.LinkedHashMap<>();
        m.put(dev.langchain4j.rag.content.ContentMetadata.SCORE, score);
        return m;
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

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0.0 || nb <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private record ScoredDoc(String source, String content, double score) {}
}
