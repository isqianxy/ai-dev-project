package com.nexus.agent.api.dto;

/**
 * 提交任务；M3 用 prompt 作为规则引擎输入（仍允许为空）。
 */
public record CreateRunRequest(String prompt) {}
