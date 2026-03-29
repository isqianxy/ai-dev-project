package com.nexus.agent.service.tool;

public record ToolExecutionResult(
        boolean success,
        String toolName,
        String output,
        String error
) {}
