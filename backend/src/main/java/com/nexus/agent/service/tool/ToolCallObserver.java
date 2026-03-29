package com.nexus.agent.service.tool;

public interface ToolCallObserver {

    String onToolInvoked(String toolName, String argumentsJson);

    void onToolResult(String toolCallId, String toolName, boolean success, String output, String error);
}
