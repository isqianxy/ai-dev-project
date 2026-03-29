package com.nexus.agent.service;

import com.nexus.agent.config.MemoryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMemoryServiceTest {

    @Test
    void shouldKeepOnlyLatestWindowSizeItems() {
        MemoryProperties properties = new MemoryProperties();
        properties.setEnabled(true);
        properties.setWindowSize(3);
        properties.setProvider("in_memory");

        SessionMemoryRepository repository = new InMemorySessionMemoryRepository(properties);
        SessionMemoryService service = new SessionMemoryService(properties, repository);
        service.appendUserMessage("s1", "u1");
        service.appendAssistantMessage("s1", "a1");
        service.appendUserMessage("s1", "u2");
        service.appendAssistantMessage("s1", "a2");

        String context = service.buildRecentContext("s1");
        assertThat(context).doesNotContain("u1");
        assertThat(context).contains("ASSISTANT: a1");
        assertThat(context).contains("USER: u2");
        assertThat(context).contains("ASSISTANT: a2");
    }

    @Test
    void shouldReturnEmptyWhenDisabled() {
        MemoryProperties properties = new MemoryProperties();
        properties.setEnabled(false);
        properties.setWindowSize(10);
        properties.setProvider("in_memory");

        SessionMemoryRepository repository = new InMemorySessionMemoryRepository(properties);
        SessionMemoryService service = new SessionMemoryService(properties, repository);
        service.appendUserMessage("s1", "u1");

        assertThat(service.buildRecentContext("s1")).isEmpty();
    }
}
