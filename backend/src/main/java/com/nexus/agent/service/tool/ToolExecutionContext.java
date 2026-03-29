package com.nexus.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Supplier;

@Component
public class ToolExecutionContext {

    private final ThreadLocal<Set<String>> approvedToolsHolder = new ThreadLocal<>();

    public <T> T withApprovedTools(Set<String> approvedTools, Supplier<T> action) {
        Set<String> previous = approvedToolsHolder.get();
        approvedToolsHolder.set(approvedTools);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                approvedToolsHolder.remove();
            } else {
                approvedToolsHolder.set(previous);
            }
        }
    }

    public boolean isToolApproved(String toolName) {
        Set<String> approved = approvedToolsHolder.get();
        return approved != null && approved.contains(toolName);
    }
}
