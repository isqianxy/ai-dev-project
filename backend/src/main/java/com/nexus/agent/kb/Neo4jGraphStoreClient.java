package com.nexus.agent.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.kb.model.KbTriple;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class Neo4jGraphStoreClient {

    private static final String UPSERT_CYPHER = """
            UNWIND $rows AS row
            MERGE (s:Entity {name: row.subject, kbId: row.kbId})
            MERGE (o:Entity {name: row.object, kbId: row.kbId})
            MERGE (s)-[r:REL {type: row.relationType, kbId: row.kbId}]->(o)
            ON CREATE SET r.firstSeenChunkId = row.chunkId
            SET r.lastSeenChunkId = row.chunkId,
                r.updatedAt = timestamp()
            """;

    private final KbBuildProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Neo4jGraphStoreClient(KbBuildProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public int upsert(List<KbTriple> triples) {
        if (triples.isEmpty()) {
            return 0;
        }
        try {
            List<Map<String, Object>> rows = triples.stream()
                    .map(t -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("kbId", t.kbId());
                        row.put("chunkId", t.chunkId());
                        row.put("subject", t.subject());
                        row.put("relationType", t.relationType());
                        row.put("object", t.object());
                        return row;
                    })
                    .toList();

            Map<String, Object> statement = new LinkedHashMap<>();
            statement.put("statement", UPSERT_CYPHER);
            statement.put("parameters", Map.of("rows", rows));
            Map<String, Object> payload = Map.of("statements", List.of(statement));

            String url = trimSlash(properties.getGraph().getEndpoint()) + properties.getGraph().getTxPath();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            applyAuth(builder);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Neo4j 写入失败，状态码=" + response.statusCode() + ", body=" + response.body());
            }
            return triples.size();
        } catch (Exception e) {
            throw new IllegalStateException("写入 Neo4j 失败: " + e.getMessage(), e);
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        String username = properties.getGraph().getUsername();
        String password = properties.getGraph().getPassword();
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
}
