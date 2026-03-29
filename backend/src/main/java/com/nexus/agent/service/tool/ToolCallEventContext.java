package com.nexus.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ToolCallEventContext {

    private final ThreadLocal<ToolCallObserver> observerHolder = new ThreadLocal<>();

    public ToolCallObserver currentObserver() {
        return observerHolder.get();
    }

    public <T> T withObserver(ToolCallObserver observer, Supplier<T> action) {
        ToolCallObserver previous = observerHolder.get();
        observerHolder.set(observer);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                observerHolder.remove();
            } else {
                observerHolder.set(previous);
            }
        }
    }
}
