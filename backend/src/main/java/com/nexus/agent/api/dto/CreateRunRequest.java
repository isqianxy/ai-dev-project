package com.nexus.agent.api.dto;

/**
 * 提交任务；M1 允许空 prompt，仅用于走通 SSE。
 */
public record CreateRunRequest(String prompt) {}
