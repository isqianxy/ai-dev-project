package com.nexus.agent.service.rag;

public record RagSnippet(
        String source,
        String content,
        double score
) {}
