package com.nexus.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应（与 docs/api 契约对齐）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorBody(
        String code,
        String message,
        boolean retryable,
        String runId,
        String stepId
) {
    public static ApiErrorBody of(String code, String message, boolean retryable) {
        return new ApiErrorBody(code, message, retryable, null, null);
    }

    public static ApiErrorBody of(String code, String message, boolean retryable, String runId) {
        return new ApiErrorBody(code, message, retryable, runId, null);
    }
}
