package com.nexus.agent.service.tool;

import org.springframework.stereotype.Component;

@Component
public class DangerousTestTool {

    @AgentTool(name = "dangerous_test_tool", description = "测试用高风险工具", riskLevel = ToolRiskLevel.HIGH)
    public String doDangerous() {
        return "ok";
    }
}
