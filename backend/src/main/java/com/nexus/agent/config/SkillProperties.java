package com.nexus.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.skill")
public class SkillProperties {

    private boolean enabled = true;
    private String generalSystemPrompt = "你是一个严谨的软件开发助手。请给出准确、可执行、结构清晰的回答。";
    private String toolSynthesisPrompt = "你是一个严谨的软件开发助手。请基于工具结果回答用户问题，禁止编造工具未返回的信息。";
    private String toolFallbackPrompt = "你是一个严谨的软件开发助手。工具调用失败时，请明确说明不确定性并给出可执行替代建议。";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGeneralSystemPrompt() {
        return generalSystemPrompt;
    }

    public void setGeneralSystemPrompt(String generalSystemPrompt) {
        this.generalSystemPrompt = generalSystemPrompt;
    }

    public String getToolSynthesisPrompt() {
        return toolSynthesisPrompt;
    }

    public void setToolSynthesisPrompt(String toolSynthesisPrompt) {
        this.toolSynthesisPrompt = toolSynthesisPrompt;
    }

    public String getToolFallbackPrompt() {
        return toolFallbackPrompt;
    }

    public void setToolFallbackPrompt(String toolFallbackPrompt) {
        this.toolFallbackPrompt = toolFallbackPrompt;
    }
}
