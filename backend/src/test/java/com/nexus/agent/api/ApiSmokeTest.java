package com.nexus.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createSession_createRun_returnsCreated() {
        ResponseEntity<Map> sessionRes = restTemplate.postForEntity("/api/v1/sessions", null, Map.class);
        assertThat(sessionRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String sessionId = (String) sessionRes.getBody().get("sessionId");
        assertThat(sessionId).isNotBlank();

        ResponseEntity<Map> runRes = restTemplate.postForEntity(
                "/api/v1/sessions/" + sessionId + "/runs",
                null,
                Map.class
        );
        assertThat(runRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(runRes.getBody().get("runId")).isNotNull();
        assertThat(runRes.getBody().get("sessionId")).isEqualTo(sessionId);
    }

    @Test
    void createRun_unknownSession_returns404() {
        ResponseEntity<Map> runRes = restTemplate.postForEntity(
                "/api/v1/sessions/00000000-0000-0000-0000-000000000000/runs",
                null,
                Map.class
        );
        assertThat(runRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runRes.getBody().get("code")).isEqualTo("SESSION_NOT_FOUND");
    }
}
