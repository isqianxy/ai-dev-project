package com.nexus.agent.service.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "agent.rag", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockRagRetriever implements RagRetriever {

    private static final List<RagSnippet> KB = List.of(
            new RagSnippet("kb://agent/overview", "mock", "Nexus Agent 支持会话、运行、SSE 事件流与工具调用。", 0.9),
            new RagSnippet("kb://agent/tools", "mock", "工具系统支持 function calling，工具有风险等级与审批联动。", 0.9),
            new RagSnippet("kb://agent/memory", "mock", "记忆层支持短期滑窗，提供 in_memory/redis 可切换存储。", 0.9),
            new RagSnippet("kb://agent/observability", "mock", "可观测性提供 traceId 贯穿事件链，便于排障。", 0.9),
            new RagSnippet("kb://agent/rag", "mock", "RAG 检索在 THINK 前注入上下文，失败时可降级为空。", 0.9)
    );

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public List<Content> retrieve(Query query) {
        String q = query == null || query.text() == null ? "" : query.text().toLowerCase(Locale.ROOT);
        int topK = 3;
        List<Content> hits = new ArrayList<>();
        for (RagSnippet item : KB) {
            if (matches(q, item.content().toLowerCase(Locale.ROOT))) {
                Metadata metadata = new Metadata().put("source", item.source()).put("score", item.score());
                hits.add(Content.from(TextSegment.from(item.content(), metadata)));
            }
            if (hits.size() >= Math.max(0, topK)) {
                break;
            }
        }
        return hits;
    }

    private static boolean matches(String q, String content) {
        if (q.isBlank()) {
            return false;
        }
        return (q.contains("tool") && content.contains("工具"))
                || (q.contains("memory") && content.contains("记忆"))
                || (q.contains("trace") && content.contains("trace"))
                || (q.contains("rag") && content.contains("rag"))
                || (q.contains("agent") && content.contains("agent"))
                || content.contains(q);
    }
}
