package com.nexus.agent.api.dto;

public record InvokeToolResponse(
        boolean success,
        String toolName,
        String output,
        String error
) {}
