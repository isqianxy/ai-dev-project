package com.nexus.agent.service.rag;

import com.nexus.agent.config.RagProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

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
            return new RagResult(properties.getProvider(), List.of(), "");
        }
        String provider = properties.getProvider();
        for (RagRetriever retriever : retrievers) {
            if (retriever.providerName().equalsIgnoreCase(provider)) {
                List<Content> contents = retriever.retrieve(Query.from(query == null ? "" : query));
                List<RagSnippet> hits = mapAndTrim(contents, Math.max(0, properties.getTopK()));
                return new RagResult(provider, hits, buildContext(hits));
            }
        }
        return new RagResult(provider, List.of(), "");
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
                    Double score = content.textSegment() == null || content.textSegment().metadata() == null
                            ? null
                            : content.textSegment().metadata().getDouble("score");
                    double safeScore = score == null ? 0.0 : score;
                    return new RagSnippet(source, text, safeScore);
                })
                .toList();
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

    public record RagResult(String provider, List<RagSnippet> hits, String context) {}
}
