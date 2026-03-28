package com.nexus.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createSession_createRun_returnsCreated() {
        ResponseEntity<JsonNode> sessionRes = restTemplate.postForEntity("/api/v1/sessions", null, JsonNode.class);
        assertThat(sessionRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String sessionId = sessionRes.getBody().get("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        ResponseEntity<JsonNode> runRes = restTemplate.postForEntity(
                "/api/v1/sessions/" + sessionId + "/runs",
                null,
                JsonNode.class
        );
        assertThat(runRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(runRes.getBody().get("runId").asText()).isNotBlank();
        assertThat(runRes.getBody().get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(runRes.getBody().get("createdAt")).isNotNull();
        assertThat(runRes.getBody().get("updatedAt")).isNotNull();
    }

    @Test
    void createRun_unknownSession_returns404() {
        ResponseEntity<JsonNode> runRes = restTemplate.postForEntity(
                "/api/v1/sessions/00000000-0000-0000-0000-000000000000/runs",
                null,
                JsonNode.class
        );
        assertThat(runRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runRes.getBody().get("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void getRun_createApproval_andResolve_shouldWork() {
        ResponseEntity<JsonNode> sessionRes = restTemplate.postForEntity("/api/v1/sessions", null, JsonNode.class);
        String sessionId = sessionRes.getBody().get("sessionId").asText();
        ResponseEntity<JsonNode> runRes = restTemplate.postForEntity(
                "/api/v1/sessions/" + sessionId + "/runs",
                null,
                JsonNode.class
        );
        String runId = runRes.getBody().get("runId").asText();

        ResponseEntity<JsonNode> getRunRes = restTemplate.getForEntity("/api/v1/runs/" + runId, JsonNode.class);
        assertThat(getRunRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRunRes.getBody().get("runId").asText()).isEqualTo(runId);
        assertThat(getRunRes.getBody().get("status")).isNotNull();

        ResponseEntity<JsonNode> createApprovalRes = restTemplate.postForEntity(
                "/api/v1/runs/" + runId + "/approvals",
                java.util.Map.of("action", "DELETE_RECORD"),
                JsonNode.class
        );
        assertThat(createApprovalRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String approvalId = createApprovalRes.getBody().get("approvalId").asText();
        assertThat(createApprovalRes.getBody().get("status").asText()).isEqualTo("PENDING");

        ResponseEntity<JsonNode> getApprovalRes = restTemplate.getForEntity("/api/v1/approvals/" + approvalId, JsonNode.class);
        assertThat(getApprovalRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getApprovalRes.getBody().get("approvalId").asText()).isEqualTo(approvalId);

        ResponseEntity<JsonNode> resolveRes = restTemplate.postForEntity(
                "/api/v1/approvals/" + approvalId + "/resolve",
                java.util.Map.of("decision", "APPROVED", "resolvedBy", "tester"),
                JsonNode.class
        );
        assertThat(resolveRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveRes.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(resolveRes.getBody().get("resolvedBy").asText()).isEqualTo("tester");
    }
}
