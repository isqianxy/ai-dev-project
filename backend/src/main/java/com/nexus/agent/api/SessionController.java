package com.nexus.agent.api;

import com.nexus.agent.api.dto.CreateSessionResponse;
import com.nexus.agent.domain.SessionRecord;
import com.nexus.agent.store.InMemorySessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final InMemorySessionStore sessionStore;

    public SessionController(InMemorySessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse createSession() {
        SessionRecord s = sessionStore.create();
        return new CreateSessionResponse(s.sessionId(), s.createdAt());
    }
}
