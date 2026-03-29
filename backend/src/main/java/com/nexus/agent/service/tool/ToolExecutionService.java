package com.nexus.agent.service.tool;

import com.nexus.agent.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ToolExecutionService {

    private final List<ToolProvider> providers;

    public ToolExecutionService(List<ToolProvider> providers) {
        this.providers = providers;
    }

    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        ToolProvider provider = providers.stream()
                .filter(p -> p.supports(toolName))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("TOOL_NOT_FOUND", "工具不存在: " + toolName));
        return provider.execute(toolName, argumentsJson);
    }

    public List<ToolDescriptor> listTools() {
        return providers.stream()
                .flatMap(p -> p.listTools().stream())
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public boolean supports(String toolName) {
        return providers.stream().anyMatch(p -> p.supports(toolName));
    }
}
