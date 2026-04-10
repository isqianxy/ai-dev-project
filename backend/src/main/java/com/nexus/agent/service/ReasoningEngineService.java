package com.nexus.agent.service;

import com.nexus.agent.config.LlmProperties;
import com.nexus.agent.domain.ApprovalRecord;
import com.nexus.agent.domain.RunRecord;
import com.nexus.agent.exception.BadRequestException;
import com.nexus.agent.service.llm.LlmReply;
import com.nexus.agent.service.llm.LlmService;
import com.nexus.agent.service.rag.RagService;
import com.nexus.agent.service.tool.ToolCallEventContext;
import com.nexus.agent.service.tool.ToolCallObserver;
import com.nexus.agent.service.tool.ToolExecutionContext;
import com.nexus.agent.service.tool.ToolExecutionResult;
import com.nexus.agent.service.tool.ToolExecutionService;
import com.nexus.agent.store.InMemoryApprovalStore;
import com.nexus.agent.store.InMemoryRunStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M4：ReAct 推理引擎 v0（支持 mock / deepseek 模型适配）。
 */
@Service
public class ReasoningEngineService {

    private static final int MAX_STEPS = 6;
    private static final long MAX_DURATION_MS = 4_000L;
    private static final String[] PHASES = {"THINK", "ACT", "OBSERVE"};
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    private final Executor asyncExecutor;
    private final InMemoryRunStore runStore;
    private final LlmService llmService;
    private final LlmProperties llmProperties;
    private final ToolExecutionService toolExecutionService;
    private final ToolExecutionContext toolExecutionContext;
    private final SkillPromptService skillPromptService;
    private final SessionMemoryService sessionMemoryService;
    private final RagService ragService;
    private final ToolCallEventContext toolCallEventContext;
    private final InMemoryApprovalStore approvalStore;

    public ReasoningEngineService(
            Executor asyncExecutor,
            InMemoryRunStore runStore,
            LlmService llmService,
            LlmProperties llmProperties,
            ToolExecutionService toolExecutionService,
            ToolExecutionContext toolExecutionContext,
            SkillPromptService skillPromptService,
            SessionMemoryService sessionMemoryService,
            RagService ragService,
            ToolCallEventContext toolCallEventContext,
            InMemoryApprovalStore approvalStore
    ) {
        this.asyncExecutor = asyncExecutor;
        this.runStore = runStore;
        this.llmService = llmService;
        this.llmProperties = llmProperties;
        this.toolExecutionService = toolExecutionService;
        this.toolExecutionContext = toolExecutionContext;
        this.skillPromptService = skillPromptService;
        this.sessionMemoryService = sessionMemoryService;
        this.ragService = ragService;
        this.toolCallEventContext = toolCallEventContext;
        this.approvalStore = approvalStore;
    }

    public void stream(SseEmitter emitter, RunRecord run) {
        runStore.markStreaming(run.runId());
        asyncExecutor.execute(() -> {
            TRACE_ID_HOLDER.set("trace-" + UUID.randomUUID());
            Instant startedAt = Instant.now();
            try {
                String provider = llmService.currentProvider();
                send(
                        emitter,
                        "run.started",
                        eventPayload("run.started", run.runId(), run.sessionId(), null, Map.of("status", "RUNNING", "engine", "REACT_V0", "provider", provider))
                );
                String historyContext = sessionMemoryService.buildRecentContext(run.sessionId());
                sessionMemoryService.appendUserMessage(run.sessionId(), run.prompt());
                RagService.RagResult ragResult = ragService.retrieve(run.sessionId(), run.prompt());
                emitRagRetrieved(emitter, run, ragResult);
                String mergedContext = mergeContext(historyContext, ragResult.context());

                ToolIntent toolIntent = parseExplicitToolIntent(run.prompt());
                if (toolIntent != null) {
                    boolean approvedBypass = approvalStore.consumeApprovedTool(run.runId(), toolIntent.toolName());
                    runToolLoop(emitter, run, startedAt, toolIntent, mergedContext, approvedBypass);
                    return;
                }

                if ("mock".equalsIgnoreCase(provider)) {
                    runMockLoop(emitter, run, startedAt);
                    return;
                }

                runDeepSeekLoop(emitter, run, startedAt, mergedContext);
            } catch (BadRequestException e) {
                try {
                    if ("TOOL_APPROVAL_REQUIRED".equals(e.getCode())) {
                        handleApprovalRequired(emitter, run, e.getMessage());
                        return;
                    }
                    runStore.markFailed(run.runId());
                    send(
                            emitter,
                            "run.failed",
                            eventPayload(
                                    "run.failed",
                                    run.runId(),
                                    run.sessionId(),
                                    null,
                                    Map.of("status", "FAILED", "reason", e.getCode(), "message", e.getMessage())
                            )
                    );
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter 可能已关闭
                }
            } catch (Exception e) {
                runStore.markFailed(run.runId());
                String msg = e.getMessage() == null ? "模型调用异常" : e.getMessage();
                String reason = msg.startsWith("CIRCUIT_OPEN:") ? "CIRCUIT_OPEN" : "MODEL_ERROR";
                try {
                    send(
                            emitter,
                            "run.failed",
                            eventPayload(
                                    "run.failed",
                                    run.runId(),
                                    run.sessionId(),
                                    null,
                                    Map.of("status", "FAILED", "reason", reason, "message", msg)
                            )
                    );
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter 可能已关闭
                }
            } finally {
                TRACE_ID_HOLDER.remove();
            }
        });
    }

    private void runDeepSeekLoop(SseEmitter emitter, RunRecord run, Instant startedAt, String historyContext) throws IOException {
        if (elapsedMs(startedAt) > llmProperties.getTimeoutMs()) {
            fail(emitter, run, "TIMEOUT", "达到最大执行时长，终止运行");
            return;
        }

        send(
                emitter,
                "reasoning.step",
                eventPayload(
                        "reasoning.step",
                        run.runId(),
                        run.sessionId(),
                        "step-1",
                        Map.of("phase", "THINK", "summary", "调用模型生成推理结果", "provider", llmService.currentProvider())
                )
        );

        String promptWithSkill = skillPromptService.buildGeneralPrompt(run.prompt(), historyContext);
        java.util.Optional<String> approvedTool = approvalStore.consumeApprovedTool(run.runId());
        LlmReply reply = toolCallEventContext.withObserver(
                buildSseToolObserver(emitter, run),
                () -> approvedTool
                        .map(tool -> toolExecutionContext.withApprovedTools(java.util.Set.of(tool), () -> llmService.generate(promptWithSkill)))
                        .orElseGet(() -> llmService.generate(promptWithSkill))
        );
        String preview = abbreviate(reply.content(), 160);
        send(
                emitter,
                "reasoning.step",
                eventPayload(
                        "reasoning.step",
                        run.runId(),
                        run.sessionId(),
                        "step-2",
                        Map.of("phase", "OBSERVE", "summary", preview, "model", reply.model(), "provider", reply.provider())
                )
        );

        send(
                emitter,
                "reasoning.step",
                eventPayload(
                        "reasoning.step",
                        run.runId(),
                        run.sessionId(),
                        "step-final",
                        Map.of("phase", "FINAL", "summary", "模型已返回结果，结束运行")
                )
        );

        runStore.markCompleted(run.runId());
        send(
                emitter,
                "run.completed",
                eventPayload(
                        "run.completed",
                        run.runId(),
                        run.sessionId(),
                        null,
                        Map.of("status", "SUCCEEDED", "answer", reply.content(), "provider", reply.provider(), "model", reply.model())
                )
        );
        sessionMemoryService.appendAssistantMessage(run.sessionId(), reply.content());
        emitter.complete();
    }

    private void runToolLoop(
            SseEmitter emitter,
            RunRecord run,
            Instant startedAt,
            ToolIntent toolIntent,
            String historyContext,
            boolean approvedBypass
    ) throws IOException {
        if (elapsedMs(startedAt) > llmProperties.getTimeoutMs()) {
            fail(emitter, run, "TIMEOUT", "达到最大执行时长，终止运行");
            return;
        }

        send(
                emitter,
                "reasoning.step",
                eventPayload(
                        "reasoning.step",
                        run.runId(),
                        run.sessionId(),
                        "step-1",
                        Map.of("phase", "THINK", "summary", "识别到显式工具调用意图，准备执行工具")
                )
        );

        send(
                emitter,
                "tool.invoked",
                eventPayload(
                        "tool.invoked",
                        run.runId(),
                        run.sessionId(),
                        "step-2",
                        Map.of("toolName", toolIntent.toolName(), "argumentsJson", toolIntent.argumentsJson())
                )
        );

        ToolExecutionResult toolResult = approvedBypass
                ? toolExecutionService.executeWithApprovedTools(toolIntent.toolName(), toolIntent.argumentsJson(), java.util.Set.of(toolIntent.toolName()))
                : toolExecutionService.execute(toolIntent.toolName(), toolIntent.argumentsJson());
        Map<String, Object> toolDetail = new LinkedHashMap<>();
        toolDetail.put("toolName", toolResult.toolName());
        toolDetail.put("success", toolResult.success());
        toolDetail.put("output", toolResult.output());
        toolDetail.put("error", toolResult.error());
        send(
                emitter,
                "tool.result",
                eventPayload(
                        "tool.result",
                        run.runId(),
                        run.sessionId(),
                        "step-3",
                        toolDetail
                )
        );

        if (!toolResult.success()) {
            fail(emitter, run, "TOOL_ERROR", "工具执行失败: " + toolResult.error());
            return;
        }

        String synthesisPrompt = skillPromptService.buildToolSynthesisPrompt(
                run.prompt(),
                toolResult.toolName(),
                toolResult.output(),
                historyContext
        );
        LlmReply reply = llmService.generate(synthesisPrompt);

        send(
                emitter,
                "reasoning.step",
                eventPayload(
                        "reasoning.step",
                        run.runId(),
                        run.sessionId(),
                        "step-final",
                        Map.of("phase", "FINAL", "summary", "工具结果已融合，生成最终回答")
                )
        );
        runStore.markCompleted(run.runId());
        send(
                emitter,
                "run.completed",
                eventPayload(
                        "run.completed",
                        run.runId(),
                        run.sessionId(),
                        null,
                        Map.of(
                                "status", "SUCCEEDED",
                                "answer", reply.content(),
                                "provider", reply.provider(),
                                "model", reply.model()
                        )
                )
        );
        sessionMemoryService.appendAssistantMessage(run.sessionId(), reply.content());
        emitter.complete();
    }

    private void runMockLoop(SseEmitter emitter, RunRecord run, Instant startedAt) throws IOException {
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
        String traceId = TRACE_ID_HOLDER.get();
        if (traceId != null && !traceId.isBlank()) {
            m.put("traceId", traceId);
        }
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

    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private ToolCallObserver buildSseToolObserver(SseEmitter emitter, RunRecord run) {
        AtomicInteger callSeq = new AtomicInteger(0);
        return new ToolCallObserver() {
            @Override
            public String onToolInvoked(String toolName, String argumentsJson) {
                String callId = "fc-" + callSeq.incrementAndGet();
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("toolCallId", callId);
                detail.put("toolName", toolName);
                detail.put("argumentsJson", argumentsJson);
                detail.put("route", "MODEL_FUNCTION_CALL");
                try {
                    send(
                            emitter,
                            "tool.invoked",
                            eventPayload(
                                    "tool.invoked",
                                    run.runId(),
                                    run.sessionId(),
                                    "fc-step-" + callSeq.get(),
                                    detail
                            )
                    );
                } catch (IOException e) {
                    throw new RuntimeException("发送 tool.invoked 事件失败: " + e.getMessage(), e);
                }
                return callId;
            }

            @Override
            public void onToolResult(String toolCallId, String toolName, boolean success, String output, String error) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("toolCallId", toolCallId);
                detail.put("toolName", toolName);
                detail.put("success", success);
                detail.put("output", output);
                detail.put("error", error);
                detail.put("route", "MODEL_FUNCTION_CALL");
                try {
                    send(
                            emitter,
                            "tool.result",
                            eventPayload(
                                    "tool.result",
                                    run.runId(),
                                    run.sessionId(),
                                    "fc-step-" + callSeq.get(),
                                    detail
                            )
                    );
                } catch (IOException e) {
                    throw new RuntimeException("发送 tool.result 事件失败: " + e.getMessage(), e);
                }
            }
        };
    }

    /**
     * 约定格式：tool://<toolName> <json>
     * 示例：tool://echo {"text":"你好"}
     */
    private static ToolIntent parseExplicitToolIntent(String prompt) {
        if (prompt == null) {
            return null;
        }
        String trimmed = prompt.trim();
        String prefix = "tool://";
        if (!trimmed.startsWith(prefix)) {
            return null;
        }
        String rest = trimmed.substring(prefix.length()).trim();
        if (rest.isBlank()) {
            return null;
        }
        int blank = rest.indexOf(' ');
        if (blank < 0) {
            return new ToolIntent(rest, "{}");
        }
        String toolName = rest.substring(0, blank).trim();
        String args = rest.substring(blank + 1).trim();
        return new ToolIntent(toolName, args.isBlank() ? "{}" : args);
    }

    private void handleApprovalRequired(SseEmitter emitter, RunRecord run, String message) throws IOException {
        String toolName = extractToolName(message);
        String action = "TOOL_EXECUTE:" + toolName;
        ApprovalRecord approval = approvalStore.create(run.runId(), run.sessionId(), action);

        send(
                emitter,
                "approval.requested",
                eventPayload(
                        "approval.requested",
                        run.runId(),
                        run.sessionId(),
                        null,
                        Map.of(
                                "approvalId", approval.approvalId(),
                                "action", approval.action(),
                                "status", approval.status(),
                                "toolName", toolName
                        )
                )
        );

        runStore.markFailed(run.runId());
        send(
                emitter,
                "run.failed",
                eventPayload(
                        "run.failed",
                        run.runId(),
                        run.sessionId(),
                        null,
                        Map.of(
                                "status", "FAILED",
                                "reason", "APPROVAL_REQUIRED",
                                "message", message,
                                "approvalId", approval.approvalId()
                        )
                )
        );
        emitter.complete();
    }

    private static String extractToolName(String message) {
        if (message == null || message.isBlank()) {
            return "unknown_tool";
        }
        int colon = message.indexOf(':');
        if (colon < 0 || colon + 1 >= message.length()) {
            return "unknown_tool";
        }
        String right = message.substring(colon + 1).trim();
        int blank = right.indexOf(' ');
        if (blank < 0) {
            return right;
        }
        return right.substring(0, blank).trim();
    }

    private void emitRagRetrieved(SseEmitter emitter, RunRecord run, RagService.RagResult ragResult) throws IOException {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("provider", ragResult.provider());
        detail.put("hitCount", ragResult.hits().size());
        detail.put("retrieverRoutes", ragResult.retrieverRoutes());
        detail.put("candidateCount", ragResult.candidateCount());
        detail.put("finalCount", ragResult.finalCount());
        detail.put("latencyMs", ragResult.latencyMs());
        detail.put("degraded", ragResult.degraded());
        detail.put("fallbackReason", ragResult.fallbackReason());
        detail.put("sources", ragResult.hits().stream().map(s -> s.source()).toList());
        send(
                emitter,
                "rag.retrieved",
                eventPayload("rag.retrieved", run.runId(), run.sessionId(), null, detail)
        );
    }

    private static String mergeContext(String historyContext, String ragContext) {
        String history = historyContext == null ? "" : historyContext.trim();
        String rag = ragContext == null ? "" : ragContext.trim();
        if (history.isEmpty()) {
            return rag;
        }
        if (rag.isEmpty()) {
            return history;
        }
        return history + "\n" + rag;
    }

    private record ToolIntent(String toolName, String argumentsJson) {}
}
