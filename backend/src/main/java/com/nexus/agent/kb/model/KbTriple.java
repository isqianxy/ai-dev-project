package com.nexus.agent.kb.model;

public record KbTriple(
        String kbId,
        String chunkId,
        String subject,
        String relationType,
        String object
) {
}
