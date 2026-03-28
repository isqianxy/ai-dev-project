package com.nexus.agent.domain;

import java.time.Instant;

public record SessionRecord(String sessionId, Instant createdAt) {}
