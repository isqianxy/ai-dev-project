package com.nexus.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.stability")
public class StabilityProperties {

    private boolean circuitBreakerEnabled = true;
    private int circuitFailureThreshold = 3;
    private int circuitOpenMs = 30000;

    public boolean isCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public int getCircuitOpenMs() {
        return circuitOpenMs;
    }

    public void setCircuitOpenMs(int circuitOpenMs) {
        this.circuitOpenMs = circuitOpenMs;
    }
}
