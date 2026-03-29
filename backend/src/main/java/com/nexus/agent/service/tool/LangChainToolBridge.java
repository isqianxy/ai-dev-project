package com.nexus.agent.service.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class LangChainToolBridge {

    private final ToolExecutionService toolExecutionService;
    private final ToolCallEventContext toolCallEventContext;

    public LangChainToolBridge(
            ToolExecutionService toolExecutionService,
            ToolCallEventContext toolCallEventContext
    ) {
        this.toolExecutionService = toolExecutionService;
        this.toolCallEventContext = toolCallEventContext;
    }

    @Tool("""
            执行平台内已注册工具。
            你必须先调用 list_tools 了解可用工具，再调用本工具。
            argumentsJson 必须是合法 JSON 字符串。
            """)
    public String executeTool(
            @P("工具名称，例如 current_time 或 echo") String toolName,
            @P("工具参数 JSON 字符串，例如 {\"text\":\"你好\"}") String argumentsJson
    ) {
        ToolCallObserver observer = toolCallEventContext.currentObserver();
        String callId = observer == null ? null : observer.onToolInvoked(toolName, normalizeArgs(argumentsJson));

        try {
            ToolExecutionResult result = toolExecutionService.execute(toolName, argumentsJson);
            if (observer != null) {
                observer.onToolResult(callId, result.toolName(), result.success(), result.output(), result.error());
            }
            if (!result.success()) {
                throw new IllegalStateException("工具执行失败: " + result.error());
            }
            return result.output();
        } catch (RuntimeException e) {
            if (observer != null) {
                observer.onToolResult(callId, toolName, false, null, e.getMessage());
            }
            throw e;
        }
    }

    @Tool("返回当前可用工具清单（名称、描述、参数类型）。")
    public String listTools() {
        ToolCallObserver observer = toolCallEventContext.currentObserver();
        String callId = observer == null ? null : observer.onToolInvoked("list_tools", "{}");
        StringBuilder sb = new StringBuilder();
        try {
            for (ToolDescriptor d : toolExecutionService.listTools()) {
                sb.append("- name=").append(d.name())
                        .append(", description=").append(d.description())
                        .append(", parameterType=").append(d.parameterType())
                        .append(", riskLevel=").append(d.riskLevel())
                        .append('\n');
            }
            String output = sb.toString();
            if (observer != null) {
                observer.onToolResult(callId, "list_tools", true, output, null);
            }
            return output;
        } catch (RuntimeException e) {
            if (observer != null) {
                observer.onToolResult(callId, "list_tools", false, null, e.getMessage());
            }
            throw e;
        }
    }

    private static String normalizeArgs(String argumentsJson) {
        return (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
    }
}
