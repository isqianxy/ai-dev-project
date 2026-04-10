package com.nexus.agent.kb.model;

public record KbChunk(
        String chunkId,
        String kbId,
        String documentId,
        String sourceUri,
        int chunkIndex,
        String content
) {
}
