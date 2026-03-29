package com.nexus.agent.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalToolProvider implements ToolProvider {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public LocalToolProvider(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public List<ToolDescriptor> listTools() {
        return toolRegistry.all().stream()
                .map(this::toDescriptor)
                .toList();
    }

    @Override
    public boolean supports(String toolName) {
        return toolRegistry.find(toolName).isPresent();
    }

    @Override
    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        ToolDefinition definition = toolRegistry.find(toolName)
                .orElseThrow(() -> new BadRequestException("TOOL_NOT_FOUND", "工具不存在: " + toolName));

        try {
            Object output;
            if (definition.parameterType() == null) {
                output = definition.method().invoke(definition.bean());
            } else {
                Object argsObj = parseArguments(argumentsJson, definition.parameterType());
                output = definition.method().invoke(definition.bean(), argsObj);
            }
            return new ToolExecutionResult(true, toolName, stringify(output), null);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            return new ToolExecutionResult(false, toolName, null, e.getMessage());
        }
    }

    private ToolDescriptor toDescriptor(ToolDefinition definition) {
        String paramType = definition.parameterType() == null ? "none" : definition.parameterType().getSimpleName();
        return new ToolDescriptor(definition.name(), definition.description(), paramType);
    }

    private Object parseArguments(String argumentsJson, Class<?> targetType) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            return objectMapper.readValue(json, targetType);
        } catch (Exception e) {
            throw new BadRequestException("TOOL_ARGUMENTS_INVALID", "工具参数解析失败: " + e.getMessage());
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
