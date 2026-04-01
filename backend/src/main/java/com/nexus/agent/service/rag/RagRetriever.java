package com.nexus.agent.service.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;

public interface RagRetriever extends ContentRetriever {

    String providerName();
}
