package com.nexus.agent.config;

import com.nexus.agent.service.tool.ToolRiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.tool-risk")
public class ToolRiskProperties {

    private boolean enabled = true;
    private ToolRiskLevel blockLevel = ToolRiskLevel.HIGH;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ToolRiskLevel getBlockLevel() {
        return blockLevel;
    }

    public void setBlockLevel(ToolRiskLevel blockLevel) {
        this.blockLevel = blockLevel;
    }
}
