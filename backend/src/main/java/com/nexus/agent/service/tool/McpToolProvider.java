package com.nexus.agent.service.tool;

import com.nexus.agent.exception.BadRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Provider 占位实现：
 * - 当前阶段只预留统一 Provider 接口，不引入具体 MCP 连接逻辑。
 * - 开启 agent.mcp.enabled 后，可在此处接入真实 MCP 客户端。
 */
@Component
@ConditionalOnProperty(prefix = "agent.mcp", name = "enabled", havingValue = "true")
public class McpToolProvider implements ToolProvider {

    @Override
    public String providerName() {
        return "mcp";
    }

    @Override
    public List<ToolDescriptor> listTools() {
        return List.of();
    }

    @Override
    public boolean supports(String toolName) {
        return false;
    }

    @Override
    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        throw new BadRequestException("MCP_NOT_IMPLEMENTED", "MCP 工具能力尚未接入: " + toolName);
    }
}
