package com.nexus.agent.service.tool;

import java.lang.reflect.Method;

public record ToolDefinition(
        String name,
        String description,
        Object bean,
        Method method,
        Class<?> parameterType
) {}
