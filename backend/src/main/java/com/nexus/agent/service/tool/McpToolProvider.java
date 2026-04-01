package com.nexus.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.agent.config.McpProperties;
import com.nexus.agent.config.ToolRiskProperties;
import com.nexus.agent.exception.BadRequestException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Provider（LangChain4j MCP 实现）：
 * - 通过 LangChain4j MCP 客户端（stdio transport）连接 MCP Server。
 * - 工具由 MCP Server 动态发现（listTools）并转发调用（callTool）。
 * - 统一在本地做风险分级与审批拦截，保障安全策略一致。
 */
@Component
@ConditionalOnProperty(prefix = "agent.mcp", name = "enabled", havingValue = "true")
public class McpToolProvider implements ToolProvider {

    private final ObjectMapper objectMapper;
    private final McpProperties mcpProperties;
    private final ToolRiskProperties toolRiskProperties;
    private final ToolExecutionContext executionContext;
    private final Map<String, String> logicalToPhysicalToolNames = new ConcurrentHashMap<>();

    private volatile McpClient client;

    public McpToolProvider(
            ObjectMapper objectMapper,
            McpProperties mcpProperties,
            ToolRiskProperties toolRiskProperties,
            ToolExecutionContext executionContext
    ) {
        this.objectMapper = objectMapper;
        this.mcpProperties = mcpProperties;
        this.toolRiskProperties = toolRiskProperties;
        this.executionContext = executionContext;
    }

    @Override
    public String providerName() {
        return "mcp";
    }

    @Override
    public List<ToolDescriptor> listTools() {
        McpClient c = getOrCreateClient();
        if (c == null) {
            return List.of();
        }
        try {
            List<ToolSpecification> tools = c.listTools();
            if (tools == null || tools.isEmpty()) {
                return List.of();
            }
            List<ToolDescriptor> result = new ArrayList<>();
            logicalToPhysicalToolNames.clear();
            for (ToolSpecification t : tools) {
                String physical = t.name();
                if (physical == null || physical.isBlank()) {
                    continue;
                }
                String logical = toLogicalToolName(physical);
                logicalToPhysicalToolNames.put(logical, physical);
                String desc = t.description() == null ? "MCP 工具: " + physical : t.description();
                ToolRiskLevel riskLevel = inferRisk(logical);
                result.add(new ToolDescriptor(logical, desc, "json", riskLevel.name()));
            }
            return result.stream().sorted(Comparator.comparing(ToolDescriptor::name)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public boolean supports(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (logicalToPhysicalToolNames.containsKey(toolName)) {
            return true;
        }
        listTools();
        return logicalToPhysicalToolNames.containsKey(toolName);
    }

    @Override
    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        if (!supports(toolName)) {
            throw new BadRequestException("TOOL_NOT_FOUND", "MCP 工具不存在或未启用: " + toolName);
        }
        ToolRiskLevel risk = inferRisk(toolName);
        enforceRiskPolicy(toolName, risk);

        McpClient c = getOrCreateClient();
        if (c == null) {
            throw new BadRequestException("MCP_UNAVAILABLE", "MCP 客户端未初始化，请检查 agent.mcp.stdio 配置");
        }
        String physicalToolName = logicalToPhysicalToolNames.getOrDefault(toolName, toolName);
        String normalizedArgumentsJson = normalizeArguments(argumentsJson);
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("mcp-" + System.nanoTime())
                    .name(physicalToolName)
                    .arguments(normalizedArgumentsJson)
                    .build();
            String output = c.executeTool(request);
            return new ToolExecutionResult(true, toolName, output, null);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            return new ToolExecutionResult(false, toolName, null, e.getMessage());
        }
    }

    private String normalizeArguments(String argumentsJson) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                return "{}";
            }
            if (!node.isObject()) {
                throw new BadRequestException("TOOL_ARGUMENTS_INVALID", "工具参数必须是 JSON 对象");
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BadRequestException("TOOL_ARGUMENTS_INVALID", "工具参数解析失败: " + e.getMessage());
        }
    }

    private ToolRiskLevel inferRisk(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("delete") || name.contains("remove") || name.contains("drop")) {
            return ToolRiskLevel.HIGH;
        }
        if (name.contains("set") || name.contains("create") || name.contains("update")
                || name.contains("write") || name.contains("insert")) {
            return ToolRiskLevel.MEDIUM;
        }
        return ToolRiskLevel.LOW;
    }

    private String toLogicalToolName(String physicalName) {
        String prefix = mcpProperties.getToolPrefix() == null ? "mcp." : mcpProperties.getToolPrefix().trim();
        if (prefix.isBlank()) {
            return physicalName;
        }
        return prefix + physicalName;
    }

    private McpClient getOrCreateClient() {
        McpClient existing = this.client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.client != null) {
                return this.client;
            }
            McpProperties.Stdio stdio = mcpProperties.getStdio();
            if (!stdio.isEnabled() || stdio.getCommand() == null || stdio.getCommand().isBlank()) {
                return null;
            }
            List<String> command = new ArrayList<>();
            command.add(stdio.getCommand().trim());
            if (stdio.getArgs() != null && !stdio.getArgs().isEmpty()) {
                command.addAll(stdio.getArgs());
            }
            McpTransport transport = new StdioMcpTransport.Builder()
                    .command(command)
                    .environment(stdio.getEnv() == null ? Map.of() : stdio.getEnv())
                    .logEvents(false)
                    .build();
            McpClient mcpClient = new DefaultMcpClient.Builder()
                    .key("default-mcp")
                    .transport(transport)
                    .initializationTimeout(Duration.ofMillis(Math.max(1000L, stdio.getInitTimeoutMs())))
                    .toolExecutionTimeout(Duration.ofMillis(Math.max(1000L, stdio.getRequestTimeoutMs())))
                    .resourcesTimeout(Duration.ofMillis(Math.max(1000L, stdio.getRequestTimeoutMs())))
                    .promptsTimeout(Duration.ofMillis(Math.max(1000L, stdio.getRequestTimeoutMs())))
                    .build();
            this.client = mcpClient;
            return mcpClient;
        }
    }

    private void enforceRiskPolicy(String toolName, ToolRiskLevel riskLevel) {
        if (!toolRiskProperties.isEnabled()) {
            return;
        }
        if (executionContext.isToolApproved(toolName)) {
            return;
        }
        ToolRiskLevel blockLevel = toolRiskProperties.getBlockLevel();
        if (riskLevel != null && blockLevel != null && riskLevel.atLeast(blockLevel)) {
            throw new BadRequestException(
                    "TOOL_APPROVAL_REQUIRED",
                    "工具风险等级过高，需人工审批后执行: " + toolName + " (risk=" + riskLevel + ")"
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        McpClient c = this.client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // ignore
            } finally {
                this.client = null;
            }
        }
    }
}
