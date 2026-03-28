package com.nexus.agent.service;

import com.nexus.agent.store.InMemoryRunStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * M1：推送模拟 SSE 事件，不调用真实 LLM。
 */
@Service
public class MockRunEventService {

    private final Executor asyncExecutor;
    private final InMemoryRunStore runStore;

    public MockRunEventService(Executor asyncExecutor, InMemoryRunStore runStore) {
        this.asyncExecutor = asyncExecutor;
        this.runStore = runStore;
    }

    public void streamMockEvents(SseEmitter emitter, String runId, String sessionId) {
        runStore.markStreaming(runId);
        asyncExecutor.execute(() -> {
            try {
                send(emitter, "run.started", eventPayload("run.started", runId, sessionId, null, "RUNNING"));
                sleep(150);
                send(emitter, "reasoning.step", eventPayload(
                        "reasoning.step", runId, sessionId, "step-mock-1",
                        Map.of("phase", "THINK", "summary", "（Mock）分析空任务")));
                sleep(150);
                send(emitter, "reasoning.step", eventPayload(
                        "reasoning.step", runId, sessionId, "step-mock-2",
                        Map.of("phase", "FINAL", "summary", "（Mock）任务完成")));
                sleep(100);
                send(emitter, "run.completed", eventPayload("run.completed", runId, sessionId, null, "SUCCEEDED"));
                runStore.markCompleted(runId);
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // emitter 可能已关闭
                }
            }
        });
    }

    private static Map<String, Object> eventPayload(
            String type,
            String runId,
            String sessionId,
            String stepId,
            Object detail
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("runId", runId);
        m.put("sessionId", sessionId);
        if (stepId != null) {
            m.put("stepId", stepId);
        }
        if (detail instanceof String s) {
            m.put("status", s);
        } else if (detail instanceof Map<?, ?> map) {
            m.put("detail", map);
        }
        return m;
    }

    private static void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
