package com.nexus.agent.service.tool;

import org.springframework.stereotype.Component;

@Component
public class TextTools {

    @AgentTool(name = "echo", description = "返回输入文本")
    public String echo(EchoToolInput input) {
        if (input == null || input.text() == null) {
            return "";
        }
        return input.text();
    }
}
