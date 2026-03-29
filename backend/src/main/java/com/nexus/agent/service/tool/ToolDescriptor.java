package com.nexus.agent.service.tool;

public record ToolDescriptor(
        String name,
        String description,
        String parameterType,
        String riskLevel
) {}
