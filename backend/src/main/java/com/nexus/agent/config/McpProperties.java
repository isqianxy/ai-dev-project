package com.nexus.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.mcp")
public class McpProperties {

    private boolean enabled = false;
    private final Stdio stdio = new Stdio();
    private String toolPrefix = "mcp.";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Stdio getStdio() {
        return stdio;
    }

    public String getToolPrefix() {
        return toolPrefix;
    }

    public void setToolPrefix(String toolPrefix) {
        this.toolPrefix = toolPrefix;
    }

    public static class Stdio {
        private boolean enabled = true;
        private String command = "";
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private long requestTimeoutMs = 10000;
        private long initTimeoutMs = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public long getInitTimeoutMs() {
            return initTimeoutMs;
        }

        public void setInitTimeoutMs(long initTimeoutMs) {
            this.initTimeoutMs = initTimeoutMs;
        }
    }
}
