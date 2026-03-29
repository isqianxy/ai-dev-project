package com.nexus.agent.api.dto;

public record ToolInfoResponse(
        String name,
        String description,
        String parameterType
) {}
