package com.nexus.agent.kb.model;

public record KbBuildResult(
        int documentCount,
        int chunkCount,
        int vectorUpsertCount,
        int tripleCount
) {
}
