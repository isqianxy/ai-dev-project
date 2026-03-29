package com.nexus.agent.service.tool;

public enum ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public boolean atLeast(ToolRiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
