package com.nexus.agent.service.rag;

public record RagSnippet(
        String source,
        String route,
        String content,
        double score
) {}
