package com.nexus.agent.service;

import com.nexus.agent.config.SkillProperties;
import org.springframework.stereotype.Service;

@Service
public class SkillPromptService {

    private final SkillProperties properties;

    public SkillPromptService(SkillProperties properties) {
        this.properties = properties;
    }

    public String buildGeneralPrompt(String userPrompt) {
        if (!properties.isEnabled()) {
            return userPrompt == null ? "" : userPrompt;
        }
        return """
                [SYSTEM]
                %s

                [USER]
                %s
                """.formatted(normalize(properties.getGeneralSystemPrompt()), normalize(userPrompt));
    }

    public String buildToolSynthesisPrompt(String userPrompt, String toolName, String toolOutput) {
        if (!properties.isEnabled()) {
            return """
                    用户请求：%s
                    工具调用结果（%s）：%s
                    请基于工具结果给出最终回答。
                    """.formatted(normalize(userPrompt), normalize(toolName), normalize(toolOutput));
        }
        return """
                [SYSTEM]
                %s

                [CONTEXT]
                用户请求：%s
                工具调用结果（%s）：%s

                [TASK]
                请基于工具结果给出最终回答。
                """.formatted(
                normalize(properties.getToolSynthesisPrompt()),
                normalize(userPrompt),
                normalize(toolName),
                normalize(toolOutput)
        );
    }

    public String buildToolFallbackPrompt(String userPrompt, String toolName, String errorMessage) {
        if (!properties.isEnabled()) {
            return normalize(userPrompt);
        }
        return """
                [SYSTEM]
                %s

                [CONTEXT]
                用户请求：%s
                工具：%s
                错误：%s

                [TASK]
                请在不依赖该工具的前提下，给出谨慎、可执行的替代答案。
                """.formatted(
                normalize(properties.getToolFallbackPrompt()),
                normalize(userPrompt),
                normalize(toolName),
                normalize(errorMessage)
        );
    }

    private static String normalize(String text) {
        return text == null ? "" : text;
    }
}
