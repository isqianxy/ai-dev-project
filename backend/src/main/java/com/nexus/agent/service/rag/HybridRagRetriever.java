package com.nexus.agent.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.config.RagProperties;
import com.nexus.agent.service.llm.LlmService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Component
@ConditionalOnProperty(prefix = "agent.rag", name = "provider", havingValue = "hybrid")
public class HybridRagRetriever implements RagRetriever, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(HybridRagRetriever.class);
    private static final double RRF_K = 60.0;

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final LlmService llmService;
    private final HttpClient httpClient;
    private final ExecutorService retrievalExecutor;

    public HybridRagRetriever(RagProperties properties, ObjectMapper objectMapper, LlmService llmService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.llmService = llmService;
        int threads = Math.max(4, Math.min(32, properties.getHybrid().getParallel().getThreads()));
        this.retrievalExecutor = Executors.newFixedThreadPool(threads, ragThreadFactory());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerName() {
        return "hybrid";
    }

    @Override
    public List<Content> retrieve(Query query) {
        long startedAt = System.currentTimeMillis();
        String q = query == null || query.text() == null ? "" : query.text().trim();
        if (q.isBlank()) {
            log.warn("[RAG-HYBRID] 查询为空，跳过检索");
            return List.of();
        }

        List<String> transformedQueries = transformQueries(q);
        CompletableFuture<RouteBatchResult> vectorFuture = CompletableFuture.supplyAsync(
                () -> executeInParallel(transformedQueries, this::retrieveFromChroma, "VECTOR"),
                retrievalExecutor
        );
        CompletableFuture<RouteBatchResult> graphFuture = CompletableFuture.supplyAsync(
                () -> executeInParallel(transformedQueries, this::retrieveFromNeo4j, "GRAPH"),
                retrievalExecutor
        );
        RouteBatchResult vectorResult = awaitRouteResult(vectorFuture, "VECTOR");
        RouteBatchResult graphResult = awaitRouteResult(graphFuture, "GRAPH");
        List<List<ScoredContent>> vectorBatches = vectorResult.batches();
        List<List<ScoredContent>> graphBatches = graphResult.batches();
        int vectorHits = vectorBatches.stream().mapToInt(List::size).sum();
        int graphHits = graphBatches.stream().mapToInt(List::size).sum();
        List<String> fallbackReasons = new ArrayList<>();
        fallbackReasons.addAll(vectorResult.fallbackReasons());
        fallbackReasons.addAll(graphResult.fallbackReasons());
        fallbackReasons = fallbackReasons.stream().distinct().toList();
        boolean degraded = !fallbackReasons.isEmpty();
        List<ScoredContent> merged = fuseByRrf(
                vectorBatches,
                graphBatches,
                Math.max(0.0, properties.getHybrid().getVectorWeight()),
                Math.max(0.0, properties.getHybrid().getGraphWeight())
        );

        if (merged.isEmpty()) {
            log.warn(
                    "[RAG-HYBRID] 检索结果为空。query='{}', transformedQueries={}, vectorHits={}, graphHits={}, chromaCollection={}, kbId={}",
                    abbreviate(q, 80),
                    transformedQueries,
                    vectorHits,
                    graphHits,
                    properties.getHybrid().getChroma().getCollection(),
                    properties.getHybrid().getKbId()
            );
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        int topK = Math.max(1, properties.getTopK());
        String fallbackSummary = fallbackReasons.isEmpty() ? "" : String.join(";", fallbackReasons);
        long latencyMs = System.currentTimeMillis() - startedAt;
        List<ScoredContent> rankedCandidates = merged.stream()
                .sorted(Comparator.comparingDouble(ScoredContent::score).reversed())
                .filter(sc -> seen.add(sc.dedupKey()))
                .toList();
        if (properties.getHybrid().getRerank().isEnabled()) {
            rankedCandidates = rerankCandidates(q, rankedCandidates, fallbackReasons);
            fallbackSummary = fallbackReasons.isEmpty() ? "" : String.join(";", fallbackReasons.stream().distinct().toList());
            degraded = !fallbackReasons.isEmpty();
        }
        boolean finalDegraded = degraded;
        String finalFallbackSummary = fallbackSummary;
        List<Content> finalHits = rankedCandidates.stream()
                .limit(topK)
                .map(sc -> toContent(
                        sc.source(),
                        sc.route(),
                        sc.text(),
                        sc.score(),
                        merged.size(),
                        transformedQueries.size(),
                        finalDegraded,
                        finalFallbackSummary,
                        latencyMs
                ))
                .toList();
        log.info(
                "[RAG-HYBRID] 检索完成。query='{}', transformedQueries={}, vectorHits={}, graphHits={}, merged={}, finalHits={}, degraded={}, fallbackReasons={}, latencyMs={}",
                abbreviate(q, 80),
                transformedQueries.size(),
                vectorHits,
                graphHits,
                merged.size(),
                finalHits.size(),
                degraded,
                fallbackReasons,
                latencyMs
        );
        return finalHits;
    }

    private List<String> transformQueries(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of();
        }
        int maxQueries = resolveMaxQueries();
        Set<String> out = new LinkedHashSet<>();
        String trimmed = originalQuery.trim();
        out.add(trimmed);

        if (properties.getHybrid().getQuery2doc().isEnabled()) {
            List<String> expanded = expandQueriesByLlm(trimmed, maxQueries);
            out.addAll(expanded);
            log.info("[RAG-HYBRID][Q2D] 启用 LLM Query2Doc，生成查询数={}", expanded.size());
        }

        String normalized = trimmed
                .replace('，', ' ')
                .replace('。', ' ')
                .replace('；', ' ')
                .replace('！', ' ')
                .replace('？', ' ')
                .replace(',', ' ')
                .replace(';', ' ')
                .replace('?', ' ')
                .replace('!', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (!normalized.isBlank()) {
            out.add(normalized);
        }

        if (out.size() < maxQueries) {
            String[] parts = trimmed.split("[，,。！？?；;\\n]+");
            for (String part : parts) {
                String candidate = part == null ? "" : part.trim();
                if (candidate.length() >= 4) {
                    out.add(candidate);
                }
                if (out.size() >= maxQueries) {
                    break;
                }
            }
        }
        return out.stream().limit(maxQueries).toList();
    }

    private int resolveMaxQueries() {
        return Math.max(1, properties.getHybrid().getQuery2doc().getMaxQueries());
    }

    private List<String> expandQueriesByLlm(String query, int maxQueries) {
        try {
            String prompt = """
                    你是企业知识库检索系统的 Query2Doc 生成器。
                    你的任务：根据用户问题生成适合向量检索与图谱检索的改写查询。
                    约束：
                    1) 输出必须是 JSON 数组字符串，例如 ["...","..."]，不要输出 markdown，不要输出解释。
                    2) 至多生成 %d 条。
                    3) 每条尽量保留原问题语义，不要编造事实。
                    4) 每条长度不超过 80 字。
                    用户问题：%s
                    """.formatted(maxQueries, query);
            String raw = llmService.generate(prompt).content();
            log.debug("[RAG-HYBRID][Q2D] LLM 原始返回长度={}", raw == null ? 0 : raw.length());
            return parseExpandedQueries(raw, maxQueries);
        } catch (Exception e) {
            log.warn("[RAG-HYBRID][Q2D] LLM Query2Doc 失败，回退规则扩写。error={}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseExpandedQueries(String raw, int maxQueries) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        try {
            JsonNode root = tryReadJsonNode(raw);
            if (root != null && root.isArray()) {
                for (JsonNode item : root) {
                    String q = item.asText("").trim();
                    if (!q.isBlank()) {
                        out.add(q);
                    }
                    if (out.size() >= maxQueries) {
                        break;
                    }
                }
                return out.stream().limit(maxQueries).toList();
            }
            if (root != null && root.isObject() && root.path("queries").isArray()) {
                for (JsonNode item : root.path("queries")) {
                    String q = item.asText("").trim();
                    if (!q.isBlank()) {
                        out.add(q);
                    }
                    if (out.size() >= maxQueries) {
                        break;
                    }
                }
                return out.stream().limit(maxQueries).toList();
            }
        } catch (Exception ignored) {
            // ignore and fallback to line parsing
        }
        String[] lines = raw.split("\\R+");
        for (String line : lines) {
            String q = line
                    .replaceFirst("^[-*\\d\\s.\\)]*", "")
                    .trim();
            if (!q.isBlank()) {
                out.add(q);
            }
            if (out.size() >= maxQueries) {
                break;
            }
        }
        return out.stream().limit(maxQueries).toList();
    }

    private JsonNode tryReadJsonNode(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            int l = raw.indexOf('[');
            int r = raw.lastIndexOf(']');
            if (l >= 0 && r > l) {
                String candidate = raw.substring(l, r + 1);
                try {
                    return objectMapper.readTree(candidate);
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    private RouteBatchResult executeInParallel(
            List<String> queries,
            Function<String, List<ScoredContent>> retriever,
            String route
    ) {
        if (queries == null || queries.isEmpty()) {
            return new RouteBatchResult(List.of(), List.of());
        }
        List<CompletableFuture<List<ScoredContent>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> retriever.apply(q), retrievalExecutor))
                .toList();
        List<List<ScoredContent>> out = new ArrayList<>(futures.size());
        List<String> fallbackReasons = new ArrayList<>();
        int taskTimeoutMs = Math.max(300, properties.getHybrid().getParallel().getTaskTimeoutMs());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(taskTimeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                log.warn("[RAG-HYBRID][{}] 并行检索任务超时，query='{}', timeoutMs={}", route, abbreviate(queries.get(i), 60), taskTimeoutMs);
                fallbackReasons.add(route.toLowerCase(Locale.ROOT) + "_task_timeout");
                out.add(List.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fallbackReasons.add(route.toLowerCase(Locale.ROOT) + "_task_interrupted");
                out.add(List.of());
            } catch (ExecutionException | CompletionException e) {
                log.warn("[RAG-HYBRID][{}] 并行检索任务异常，query='{}', error={}", route, abbreviate(queries.get(i), 60), e.getMessage());
                fallbackReasons.add(route.toLowerCase(Locale.ROOT) + "_task_error");
                out.add(List.of());
            }
        }
        return new RouteBatchResult(out, fallbackReasons.stream().distinct().toList());
    }

    private RouteBatchResult awaitRouteResult(
            CompletableFuture<RouteBatchResult> routeFuture,
            String route
    ) {
        int routeTimeoutMs = Math.max(500, properties.getHybrid().getParallel().getRouteTimeoutMs());
        try {
            return routeFuture.get(routeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[RAG-HYBRID][{}] 路由检索超时，timeoutMs={}", route, routeTimeoutMs);
            return new RouteBatchResult(List.of(), List.of(route.toLowerCase(Locale.ROOT) + "_route_timeout"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RouteBatchResult(List.of(), List.of(route.toLowerCase(Locale.ROOT) + "_route_interrupted"));
        } catch (ExecutionException e) {
            log.warn("[RAG-HYBRID][{}] 路由检索异常，error={}", route, e.getMessage());
            return new RouteBatchResult(List.of(), List.of(route.toLowerCase(Locale.ROOT) + "_route_error"));
        }
    }

    private List<ScoredContent> fuseByRrf(
            List<List<ScoredContent>> vectorBatches,
            List<List<ScoredContent>> graphBatches,
            double vectorWeight,
            double graphWeight
    ) {
        Map<String, FusionAccumulator> acc = new LinkedHashMap<>();
        mergeRouteByRrf(vectorBatches, vectorWeight, acc);
        mergeRouteByRrf(graphBatches, graphWeight, acc);
        if (acc.isEmpty()) {
            return List.of();
        }
        return acc.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::score).reversed())
                .map(x -> new ScoredContent(x.source(), x.route(), x.text(), x.score()))
                .toList();
    }

    private void mergeRouteByRrf(
            List<List<ScoredContent>> batches,
            double weight,
            Map<String, FusionAccumulator> acc
    ) {
        if (batches == null || batches.isEmpty() || weight <= 0.0) {
            return;
        }
        for (List<ScoredContent> batch : batches) {
            for (int rank = 0; rank < batch.size(); rank++) {
                ScoredContent item = batch.get(rank);
                String key = item.dedupKey();
                FusionAccumulator current = acc.get(key);
                if (current == null) {
                    current = new FusionAccumulator(item.source(), item.route(), item.text(), 0.0);
                    acc.put(key, current);
                }
                current.add(weight * (1.0 / (RRF_K + rank + 1.0)));
            }
        }
    }

    private List<ScoredContent> rerankCandidates(String query, List<ScoredContent> rankedCandidates, List<String> fallbackReasons) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return rankedCandidates;
        }
        RagProperties.Rerank rerank = properties.getHybrid().getRerank();
        int limit = Math.max(1, Math.min(rerank.getCandidateTopK(), rankedCandidates.size()));
        List<ScoredContent> head = rankedCandidates.subList(0, limit);
        List<ScoredContent> tail = rankedCandidates.subList(limit, rankedCandidates.size());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("texts", head.stream().map(ScoredContent::text).toList());
            String path = rerank.getPath();
            if (path == null || path.isBlank()) {
                path = "/rerank";
            }
            String url = trimSlash(rerank.getEndpoint()) + (path.startsWith("/") ? path : "/" + path);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(500, rerank.getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                fallbackReasons.add("rerank_status_" + response.statusCode());
                log.warn("[RAG-HYBRID][RERANK] 请求失败。status={}, body={}", response.statusCode(), abbreviate(response.body(), 200));
                return rankedCandidates;
            }
            Map<Integer, Double> scoreMap = parseRerankScores(response.body());
            if (scoreMap.isEmpty()) {
                fallbackReasons.add("rerank_empty");
                log.warn("[RAG-HYBRID][RERANK] 返回空评分，回退原排序");
                return rankedCandidates;
            }
            double weight = Math.max(0.0, Math.min(1.0, rerank.getWeight()));
            List<Double> rerankRaw = new ArrayList<>(head.size());
            for (int i = 0; i < head.size(); i++) {
                rerankRaw.add(scoreMap.getOrDefault(i, Double.NaN));
            }
            List<Double> normalized = normalizeScores(rerankRaw);
            List<ScoredContent> rerankedHead = new ArrayList<>(head.size());
            for (int i = 0; i < head.size(); i++) {
                ScoredContent item = head.get(i);
                double rerankScore = Double.isNaN(normalized.get(i)) ? item.score() : normalized.get(i);
                double fused = item.score() * (1.0 - weight) + rerankScore * weight;
                rerankedHead.add(new ScoredContent(item.source(), item.route(), item.text(), fused));
            }
            rerankedHead = rerankedHead.stream()
                    .sorted(Comparator.comparingDouble(ScoredContent::score).reversed())
                    .toList();
            List<ScoredContent> out = new ArrayList<>(rankedCandidates.size());
            out.addAll(rerankedHead);
            out.addAll(tail);
            log.info("[RAG-HYBRID][RERANK] 完成重排。candidateTopK={}, weight={}", limit, weight);
            return out;
        } catch (Exception e) {
            fallbackReasons.add("rerank_exception");
            log.warn("[RAG-HYBRID][RERANK] 重排异常，回退原排序。error={}", e.getMessage());
            return rankedCandidates;
        }
    }

    private Map<Integer, Double> parseRerankScores(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode arr = root;
            if (root.isObject() && root.path("results").isArray()) {
                arr = root.path("results");
            } else if (root.isObject() && root.path("data").isArray()) {
                arr = root.path("data");
            }
            if (!arr.isArray()) {
                return Map.of();
            }
            Map<Integer, Double> out = new LinkedHashMap<>();
            for (JsonNode node : arr) {
                int index = node.path("index").asInt(-1);
                if (index < 0) {
                    continue;
                }
                JsonNode scoreNode = node.path("score");
                if (!scoreNode.isNumber()) {
                    continue;
                }
                out.put(index, scoreNode.asDouble());
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Double> normalizeScores(List<Double> rawScores) {
        if (rawScores == null || rawScores.isEmpty()) {
            return List.of();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Double raw : rawScores) {
            if (raw == null || Double.isNaN(raw)) {
                continue;
            }
            min = Math.min(min, raw);
            max = Math.max(max, raw);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || max - min < 1e-9) {
            return rawScores.stream().map(v -> Double.isNaN(v) ? Double.NaN : 1.0).toList();
        }
        List<Double> out = new ArrayList<>(rawScores.size());
        for (Double raw : rawScores) {
            if (raw == null || Double.isNaN(raw)) {
                out.add(Double.NaN);
            } else {
                out.add((raw - min) / (max - min));
            }
        }
        return out;
    }

    private List<ScoredContent> retrieveFromChroma(String query) {
        try {
            float[] queryEmbedding = embedQuery(query);
            if (queryEmbedding.length == 0) {
                log.warn("[RAG-HYBRID][VECTOR] 查询向量为空，provider={}", properties.getEmbedding().getProvider());
                return List.of();
            }

            String collectionId = resolveCollectionId(properties.getHybrid().getChroma().getCollection());
            if (collectionId.isBlank()) {
                log.warn(
                        "[RAG-HYBRID][VECTOR] 未找到 Chroma collection。collection={}",
                        properties.getHybrid().getChroma().getCollection()
                );
                return List.of();
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query_embeddings", List.of(toFloatList(queryEmbedding)));
            payload.put("n_results", Math.max(1, properties.getHybrid().getVectorTopK()));
            payload.put("include", List.of("documents", "metadatas", "distances"));

            String url = trimSlash(properties.getHybrid().getChroma().getEndpoint()) + "/api/v1/collections/" + collectionId + "/query";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(500, properties.getHybrid().getChroma().getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[RAG-HYBRID][VECTOR] Chroma query 失败。status={}, body={}",
                        response.statusCode(),
                        abbreviate(response.body(), 200)
                );
                return List.of();
            }
            List<ScoredContent> hits = parseChromaQueryResult(response.body());
            log.info(
                    "[RAG-HYBRID][VECTOR] 命中={}，embeddingDim={}，collection={}",
                    hits.size(),
                    queryEmbedding.length,
                    properties.getHybrid().getChroma().getCollection()
            );
            return hits;
        } catch (Exception e) {
            log.warn("[RAG-HYBRID][VECTOR] 检索异常: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ScoredContent> parseChromaQueryResult(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode docs = root.path("documents");
            JsonNode metas = root.path("metadatas");
            JsonNode distances = root.path("distances");
            JsonNode ids = root.path("ids");
            if (!docs.isArray() || docs.isEmpty() || !docs.get(0).isArray()) {
                return List.of();
            }

            List<ScoredContent> out = new ArrayList<>();
            JsonNode docs0 = docs.get(0);
            JsonNode metas0 = metas.isArray() && !metas.isEmpty() ? metas.get(0) : null;
            JsonNode dist0 = distances.isArray() && !distances.isEmpty() ? distances.get(0) : null;
            JsonNode ids0 = ids.isArray() && !ids.isEmpty() ? ids.get(0) : null;
            for (int i = 0; i < docs0.size(); i++) {
                String text = docs0.get(i).asText("");
                JsonNode meta = metas0 != null && metas0.isArray() && i < metas0.size() ? metas0.get(i) : null;
                String chunkId = ids0 != null && ids0.isArray() && i < ids0.size() ? ids0.get(i).asText("") : "";
                String source = meta != null ? meta.path("sourceUri").asText("") : "";
                if (source == null || source.isBlank()) {
                    source = meta != null ? meta.path("source").asText("") : "";
                }
                if (source == null || source.isBlank()) {
                    source = chunkId.isBlank() ? "kb://chroma/unknown" : "kb://chroma/" + chunkId;
                }

                double distance = dist0 != null && dist0.isArray() && i < dist0.size() ? dist0.get(i).asDouble(1.0) : 1.0;
                double score = 1.0 / (1.0 + Math.max(0.0, distance));
                out.add(new ScoredContent(source, "vector", text, score));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ScoredContent> retrieveFromNeo4j(String query) {
        try {
            List<String> terms = extractTerms(query);
            if (terms.isEmpty()) {
                log.warn("[RAG-HYBRID][GRAPH] 未提取到有效关键词，跳过图检索");
                return List.of();
            }

            String statement = """
                    UNWIND $terms AS term
                    MATCH (a:Entity)-[r:REL]->(b:Entity)
                    WHERE a.kbId = $kbId AND b.kbId = $kbId
                      AND (toLower(a.name) CONTAINS term OR toLower(b.name) CONTAINS term)
                    RETURN DISTINCT a.name AS subject,
                                    r.type AS relationType,
                                    b.name AS object,
                                    coalesce(r.lastSeenChunkId, r.firstSeenChunkId) AS chunkId
                    LIMIT $limit
                    """;
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("terms", terms);
            parameters.put("kbId", properties.getHybrid().getKbId());
            parameters.put("limit", Math.max(1, properties.getHybrid().getGraphTopK()));

            Map<String, Object> payload = Map.of(
                    "statements",
                    List.of(Map.of("statement", statement, "parameters", parameters))
            );

            String url = trimSlash(properties.getHybrid().getNeo4j().getEndpoint()) + properties.getHybrid().getNeo4j().getTxPath();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(500, properties.getHybrid().getNeo4j().getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            applyNeo4jAuth(builder);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[RAG-HYBRID][GRAPH] Neo4j query 失败。status={}, body={}",
                        response.statusCode(),
                        abbreviate(response.body(), 200)
                );
                return List.of();
            }
            List<ScoredContent> hits = parseNeo4jResult(response.body());
            hits = hydrateGraphHitsWithChunkText(hits);
            log.info(
                    "[RAG-HYBRID][GRAPH] 命中={}，terms={}，kbId={}",
                    hits.size(),
                    terms,
                    properties.getHybrid().getKbId()
            );
            return hits;
        } catch (Exception e) {
            log.warn("[RAG-HYBRID][GRAPH] 检索异常: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ScoredContent> hydrateGraphHitsWithChunkText(List<ScoredContent> graphHits) {
        if (graphHits == null || graphHits.isEmpty()) {
            return List.of();
        }
        List<String> chunkIds = graphHits.stream()
                .map(ScoredContent::source)
                .filter(source -> source != null && source.startsWith("kg://chunk/"))
                .map(source -> source.substring("kg://chunk/".length()))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return graphHits;
        }
        String collectionId = resolveCollectionId(properties.getHybrid().getChroma().getCollection());
        if (collectionId.isBlank()) {
            log.warn("[RAG-HYBRID][GRAPH] 回填 chunk 文本失败：未找到 Chroma collection={}",
                    properties.getHybrid().getChroma().getCollection());
            return graphHits;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ids", chunkIds);
            payload.put("include", List.of("documents", "metadatas"));
            String url = trimSlash(properties.getHybrid().getChroma().getEndpoint()) + "/api/v1/collections/" + collectionId + "/get";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(500, properties.getHybrid().getChroma().getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[RAG-HYBRID][GRAPH] 回填 chunk 文本失败。status={}, body={}",
                        response.statusCode(), abbreviate(response.body(), 200));
                return graphHits;
            }
            Map<String, String> chunkTextMap = parseChromaGetDocuments(response.body());
            if (chunkTextMap.isEmpty()) {
                return graphHits;
            }
            List<ScoredContent> hydrated = new ArrayList<>(graphHits.size());
            for (ScoredContent hit : graphHits) {
                String source = hit.source();
                if (source == null || !source.startsWith("kg://chunk/")) {
                    hydrated.add(hit);
                    continue;
                }
                String chunkId = source.substring("kg://chunk/".length());
                String chunkText = chunkTextMap.get(chunkId);
                if (chunkText == null || chunkText.isBlank()) {
                    hydrated.add(hit);
                    continue;
                }
                String mergedText = hit.text() + "\n[chunk] " + chunkText;
                hydrated.add(new ScoredContent(hit.source(), hit.route(), mergedText, hit.score()));
            }
            return hydrated;
        } catch (Exception e) {
            log.warn("[RAG-HYBRID][GRAPH] 回填 chunk 文本异常: {}", e.getMessage());
            return graphHits;
        }
    }

    private Map<String, String> parseChromaGetDocuments(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode ids = root.path("ids");
            JsonNode docs = root.path("documents");
            if (!ids.isArray() || !docs.isArray()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            int size = Math.min(ids.size(), docs.size());
            for (int i = 0; i < size; i++) {
                String id = ids.get(i).asText("");
                String text = docs.get(i).asText("");
                if (!id.isBlank() && !text.isBlank()) {
                    out.put(id, text);
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<ScoredContent> parseNeo4jResult(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return List.of();
            }
            JsonNode data = results.get(0).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<ScoredContent> out = new ArrayList<>();
            int rank = 0;
            for (JsonNode row : data) {
                JsonNode fields = row.path("row");
                if (!fields.isArray() || fields.size() < 4) {
                    continue;
                }
                String subject = fields.get(0).asText("");
                String relation = fields.get(1).asText("");
                String object = fields.get(2).asText("");
                String chunkId = fields.get(3).asText("");
                if (subject.isBlank() || relation.isBlank() || object.isBlank()) {
                    continue;
                }
                String text = subject + " -> " + relation + " -> " + object;
                String source = chunkId == null || chunkId.isBlank() ? "kg://path" : "kg://chunk/" + chunkId;
                double score = 1.0 / (1.0 + rank);
                out.add(new ScoredContent(source, "graph", text, score));
                rank++;
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private float[] embedQuery(String query) {
        RagProperties.Embedding embedding = properties.getEmbedding();
        String provider = embedding.getProvider();
        float[] vec;
        if (provider == null || provider.isBlank() || "openai".equalsIgnoreCase(provider)) {
            log.debug("[RAG-HYBRID] 使用 OpenAI 兼容 embedding");
            vec = embedQueryByOpenAi(query);
        } else if ("tei".equalsIgnoreCase(provider)) {
            log.debug("[RAG-HYBRID] 使用 TEI embedding");
            vec = embedQueryByTei(query);
        } else {
            log.warn("[RAG-HYBRID] 未知 embedding provider={}, 返回空向量", provider);
            vec = new float[0];
        }
        if (vec.length == 0) {
            log.warn("[RAG-HYBRID] 远端 embedding 为空，回退 hash embedding");
            return hashEmbedding(query);
        }
        return vec;
    }

    private float[] embedQueryByOpenAi(String query) {
        RagProperties.Embedding embedding = properties.getEmbedding();
        if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()
                || embedding.getApiKey() == null || embedding.getApiKey().isBlank()) {
            return new float[0];
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
            Response<Embedding> response = model.embed(query);
            if (response == null || response.content() == null) {
                return new float[0];
            }
            return response.content().vector();
        } catch (Exception e) {
            log.warn("[RAG-HYBRID] 查询向量解析失败: {}", e.getMessage());
            return new float[0];
        }
    }

    private float[] embedQueryByTei(String query) {
        RagProperties.Embedding embedding = properties.getEmbedding();
        if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()) {
            return new float[0];
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", query);
            payload.put("normalize", true);
            String path = embedding.getTeiPath();
            if (path == null || path.isBlank()) {
                path = "/embed";
            }
            String url = trimSlash(embedding.getBaseUrl()) + (path.startsWith("/") ? path : "/" + path);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(1000, embedding.getTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[RAG-HYBRID] TEI embedding 请求失败，status={}, body={}",
                        response.statusCode(),
                        abbreviate(response.body(), 200)
                );
                return new float[0];
            }
            return parseEmbedding(response.body());
        } catch (Exception e) {
            log.warn("[RAG-HYBRID] TEI embedding 异常: {}", e.getMessage());
            return new float[0];
        }
    }

    private float[] parseEmbedding(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isArray() && !root.isEmpty() && root.get(0).isArray()) {
                return toFloatArray(root.get(0));
            }
            if (root.isArray() && !root.isEmpty() && root.get(0).isNumber()) {
                return toFloatArray(root);
            }
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                return toFloatArray(data.get(0).path("embedding"));
            }
            return new float[0];
        } catch (Exception e) {
            return new float[0];
        }
    }

    private String resolveCollectionId(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return "";
        }
        try {
            String url = trimSlash(properties.getHybrid().getChroma().getEndpoint()) + "/api/v1/collections";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                return "";
            }
            for (JsonNode node : root) {
                if (collectionName.equals(node.path("name").asText(""))) {
                    return node.path("id").asText("");
                }
            }
            return "";
        } catch (Exception e) {
            log.warn("[RAG-HYBRID] 解析 Chroma collection 列表失败: {}", e.getMessage());
            return "";
        }
    }

    private static Content toContent(
            String source,
            String route,
            String text,
            double score,
            int candidateCount,
            int transformedQueryCount,
            boolean degraded,
            String fallbackReason,
            long latencyMs
    ) {
        Metadata metadata = new Metadata()
                .put("source", source)
                .put("route", route)
                .put("score", score)
                .put("candidateCount", candidateCount)
                .put("transformedQueryCount", transformedQueryCount)
                .put("degraded", degraded ? 1 : 0)
                .put("fallbackReason", fallbackReason == null ? "" : fallbackReason)
                .put("latencyMs", latencyMs);
        return Content.from(TextSegment.from(text, metadata), scoreMetadata(score));
    }

    private static Map<dev.langchain4j.rag.content.ContentMetadata, Object> scoreMetadata(double score) {
        Map<dev.langchain4j.rag.content.ContentMetadata, Object> m = new LinkedHashMap<>();
        m.put(dev.langchain4j.rag.content.ContentMetadata.SCORE, score);
        return m;
    }

    private static List<String> extractTerms(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");
        List<String> terms = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.length() >= 2) {
                terms.add(t);
            }
        }
        if (terms.isEmpty() && !lower.isBlank()) {
            terms.add(lower);
        }
        return terms.stream().distinct().limit(8).toList();
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private static float[] toFloatArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new float[0];
        }
        float[] out = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = (float) node.get(i).asDouble();
        }
        return out;
    }

    private static float[] hashEmbedding(String text) {
        final int dim = 128;
        float[] vec = new float[dim];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            int idx = Math.floorMod(normalized.charAt(i) * 31 + i, dim);
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

    private void applyNeo4jAuth(HttpRequest.Builder builder) {
        String username = properties.getHybrid().getNeo4j().getUsername();
        String password = properties.getHybrid().getNeo4j().getPassword();
        if (username == null || username.isBlank()) {
            return;
        }
        String raw = username + ":" + (password == null ? "" : password);
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + encoded);
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private static ThreadFactory ragThreadFactory() {
        AtomicInteger seq = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("rag-retrieval-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record ScoredContent(String source, String route, String text, double score) {
        private String dedupKey() {
            return source + "|" + text;
        }
    }

    private record RouteBatchResult(List<List<ScoredContent>> batches, List<String> fallbackReasons) {}

    private static final class FusionAccumulator {
        private final String source;
        private final String route;
        private final String text;
        private double score;

        private FusionAccumulator(String source, String route, String text, double score) {
            this.source = source;
            this.route = route;
            this.text = text;
            this.score = score;
        }

        private void add(double delta) {
            this.score += delta;
        }

        private String source() {
            return source;
        }

        private String route() {
            return route;
        }

        private String text() {
            return text;
        }

        private double score() {
            return score;
        }
    }

    @Override
    public void destroy() {
        retrievalExecutor.shutdown();
    }
}
