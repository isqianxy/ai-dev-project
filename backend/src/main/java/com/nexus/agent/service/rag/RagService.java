package com.nexus.agent.service.rag;

import com.nexus.agent.config.RagProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RagService {

    private final RagProperties properties;
    private final List<RagRetriever> retrievers;

    public RagService(RagProperties properties, List<RagRetriever> retrievers) {
        this.properties = properties;
        this.retrievers = retrievers;
    }

    public RagResult retrieve(String sessionId, String query) {
        if (!properties.isEnabled()) {
            return new RagResult(properties.getProvider(), List.of(), "", List.of(), 0, 0, 0L, false, "");
        }
        String provider = properties.getProvider();
        for (RagRetriever retriever : retrievers) {
            if (retriever.providerName().equalsIgnoreCase(provider)) {
                List<Content> contents = retriever.retrieve(Query.from(query == null ? "" : query));
                List<RagSnippet> hits = mapAndTrim(contents, Math.max(0, properties.getTopK()));
                int candidateCount = extractCandidateCount(contents, hits.size());
                long latencyMs = extractLatencyMs(contents);
                boolean degraded = extractDegraded(contents);
                String fallbackReason = extractFallbackReason(contents);
                List<String> retrieverRoutes = extractRetrieverRoutes(hits);
                return new RagResult(
                        provider,
                        hits,
                        buildContext(hits),
                        retrieverRoutes,
                        candidateCount,
                        hits.size(),
                        latencyMs,
                        degraded,
                        fallbackReason
                );
            }
        }
        return new RagResult(provider, List.of(), "", List.of(), 0, 0, 0L, false, "");
    }

    private static List<RagSnippet> mapAndTrim(List<Content> contents, int topK) {
        if (contents == null || contents.isEmpty() || topK <= 0) {
            return List.of();
        }
        return contents.stream()
                .limit(topK)
                .map(content -> {
                    String text = content.textSegment() == null ? "" : content.textSegment().text();
                    String source = content.textSegment() == null || content.textSegment().metadata() == null
                            ? "kb://unknown"
                            : content.textSegment().metadata().getString("source");
                    if (source == null || source.isBlank()) {
                        source = "kb://unknown";
                    }
                    String route = content.textSegment() == null || content.textSegment().metadata() == null
                            ? "unknown"
                            : content.textSegment().metadata().getString("route");
                    if (route == null || route.isBlank()) {
                        route = "unknown";
                    }
                    Double score = content.textSegment() == null || content.textSegment().metadata() == null
                            ? null
                            : content.textSegment().metadata().getDouble("score");
                    double safeScore = score == null ? 0.0 : score;
                    return new RagSnippet(source, route, text, safeScore);
                })
                .toList();
    }

    private static int extractCandidateCount(List<Content> contents, int fallback) {
        String raw = readMetadataValue(contents, "candidateCount");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long extractLatencyMs(List<Content> contents) {
        String raw = readMetadataValue(contents, "latencyMs");
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean extractDegraded(List<Content> contents) {
        String raw = readMetadataValue(contents, "degraded");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return "1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim());
    }

    private static String extractFallbackReason(List<Content> contents) {
        String raw = readMetadataValue(contents, "fallbackReason");
        return raw == null ? "" : raw;
    }

    private static String readMetadataValue(List<Content> contents, String key) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        for (Content content : contents) {
            if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
                continue;
            }
            String raw = content.textSegment().metadata().getString(key);
            if (raw != null && !raw.isBlank()) {
                return raw;
            }
        }
        return null;
    }

    private static List<String> extractRetrieverRoutes(List<RagSnippet> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Set<String> routes = new LinkedHashSet<>();
        for (RagSnippet hit : hits) {
            if (hit.route() != null && !hit.route().isBlank()) {
                routes.add(hit.route());
            }
        }
        return routes.stream().toList();
    }

    private static String buildContext(List<RagSnippet> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RagSnippet hit : hits) {
            sb.append("[").append(hit.source()).append("] ").append(hit.content()).append('\n');
        }
        return sb.toString().trim();
    }

    public record RagResult(
            String provider,
            List<RagSnippet> hits,
            String context,
            List<String> retrieverRoutes,
            int candidateCount,
            int finalCount,
            long latencyMs,
            boolean degraded,
            String fallbackReason
    ) {}
}
