package com.nexus.agent.service;

import com.nexus.agent.domain.RunRecord;
import com.nexus.agent.store.InMemoryRunStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * M3：ReAct 推理引擎 v0（规则驱动，不接真实 LLM）。
 */
@Service
public class ReasoningEngineService {

    private static final int MAX_STEPS = 6;
    private static final long MAX_DURATION_MS = 4_000L;
    private static final String[] PHASES = {"THINK", "ACT", "OBSERVE"};

    private final Executor asyncExecutor;
    private final InMemoryRunStore runStore;

    public ReasoningEngineService(Executor asyncExecutor, InMemoryRunStore runStore) {
        this.asyncExecutor = asyncExecutor;
        this.runStore = runStore;
    }

    public void stream(SseEmitter emitter, RunRecord run) {
        runStore.markStreaming(run.runId());
        asyncExecutor.execute(() -> {
            Instant startedAt = Instant.now();
            try {
                send(
                        emitter,
                        "run.started",
                        eventPayload("run.started", run.runId(), run.sessionId(), null, Map.of("status", "RUNNING", "engine", "REACT_V0"))
                );

                boolean forceUnsolvable = run.prompt().toUpperCase().contains("UNSOLVABLE");
                for (int step = 1; step <= MAX_STEPS; step++) {
                    if (elapsedMs(startedAt) > MAX_DURATION_MS) {
                        fail(emitter, run, "TIMEOUT", "达到最大执行时长，终止运行");
                        return;
                    }

                    String phase = PHASES[(step - 1) % PHASES.length];
                    String stepId = "step-" + step;
                    send(
                            emitter,
                            "reasoning.step",
                            eventPayload(
                                    "reasoning.step",
                                    run.runId(),
                                    run.sessionId(),
                                    stepId,
                                    Map.of(
                                            "phase", phase,
                                            "summary", "M3 规则推理步骤",
                                            "step", step
                                    )
                            )
                    );
                    sleep(120);

                    if (forceUnsolvable && step >= 3 && "OBSERVE".equals(phase)) {
                        fail(emitter, run, "UNSOLVABLE", "触发不可解判定，终止运行");
                        return;
                    }

                    if (!forceUnsolvable && step == 4) {
                        send(
                                emitter,
                                "reasoning.step",
                                eventPayload(
                                        "reasoning.step",
                                        run.runId(),
                                        run.sessionId(),
                                        "step-final",
                                        Map.of("phase", "FINAL", "summary", "达到完成条件，结束运行")
                                )
                        );
                        runStore.markCompleted(run.runId());
                        send(emitter, "run.completed", eventPayload("run.completed", run.runId(), run.sessionId(), null, "SUCCEEDED"));
                        emitter.complete();
                        return;
                    }
                }

                fail(emitter, run, "MAX_STEPS", "达到最大步骤数，终止运行");
            } catch (Exception e) {
                runStore.markFailed(run.runId());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // emitter 可能已关闭
                }
            }
        });
    }

    private void fail(SseEmitter emitter, RunRecord run, String reason, String message) throws IOException {
        runStore.markFailed(run.runId());
        send(
                emitter,
                "run.failed",
                eventPayload(
                        "run.failed",
                        run.runId(),
                        run.sessionId(),
                        null,
                        Map.of("status", "FAILED", "reason", reason, "message", message)
                )
        );
        emitter.complete();
    }

    private static long elapsedMs(Instant start) {
        return Instant.now().toEpochMilli() - start.toEpochMilli();
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
