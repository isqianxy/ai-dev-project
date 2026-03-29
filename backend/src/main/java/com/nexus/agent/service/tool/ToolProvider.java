package com.nexus.agent.service.tool;

import java.util.List;

public interface ToolProvider {

    String providerName();

    List<ToolDescriptor> listTools();

    boolean supports(String toolName);

    ToolExecutionResult execute(String toolName, String argumentsJson);
}
