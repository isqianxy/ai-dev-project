package com.nexus.agent.service.tool;

import com.nexus.agent.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class ToolExecutionService {

    private final List<ToolProvider> providers;
    private final ToolExecutionContext executionContext;

    public ToolExecutionService(List<ToolProvider> providers, ToolExecutionContext executionContext) {
        this.providers = providers;
        this.executionContext = executionContext;
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

    public ToolExecutionResult executeWithApprovedTools(String toolName, String argumentsJson, Set<String> approvedTools) {
        Set<String> safeApprovedTools = approvedTools == null ? Set.of() : approvedTools;
        return executionContext.withApprovedTools(safeApprovedTools, () -> execute(toolName, argumentsJson));
    }
}
