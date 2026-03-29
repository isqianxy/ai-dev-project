package com.nexus.agent.service.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainToolBridgeTest {

    @Test
    void executeTool_shouldNotifyObserver() {
        ToolProvider provider = new FakeToolProvider("echo", new ToolExecutionResult(true, "echo", "hi", null));
        ToolExecutionService toolExecutionService = new ToolExecutionService(List.of(provider));

        ToolCallEventContext context = new ToolCallEventContext();
        LangChainToolBridge bridge = new LangChainToolBridge(toolExecutionService, context);

        CapturingObserver observer = new CapturingObserver();
        String output = context.withObserver(observer, () -> bridge.executeTool("echo", "{\"text\":\"hi\"}"));

        assertThat(output).isEqualTo("hi");
        assertThat(observer.invokedToolName).isEqualTo("echo");
        assertThat(observer.invokedArgumentsJson).isEqualTo("{\"text\":\"hi\"}");
        assertThat(observer.resultSuccess).isTrue();
        assertThat(observer.resultOutput).isEqualTo("hi");
        assertThat(observer.resultError).isNull();
    }

    @Test
    void executeTool_whenFailure_shouldNotifyObserverAndThrow() {
        ToolProvider provider = new FakeToolProvider("echo", new ToolExecutionResult(false, "echo", null, "bad"));
        ToolExecutionService toolExecutionService = new ToolExecutionService(List.of(provider));

        ToolCallEventContext context = new ToolCallEventContext();
        LangChainToolBridge bridge = new LangChainToolBridge(toolExecutionService, context);

        CapturingObserver observer = new CapturingObserver();
        assertThatThrownBy(() -> context.withObserver(observer, () -> bridge.executeTool("echo", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具执行失败");

        assertThat(observer.invokedToolName).isEqualTo("echo");
        assertThat(observer.resultSuccess).isFalse();
        assertThat(observer.resultError).contains("工具执行失败");
    }

    private static class CapturingObserver implements ToolCallObserver {
        private String invokedToolName;
        private String invokedArgumentsJson;
        private String resultToolCallId;
        private boolean resultSuccess;
        private String resultOutput;
        private String resultError;

        @Override
        public String onToolInvoked(String toolName, String argumentsJson) {
            this.invokedToolName = toolName;
            this.invokedArgumentsJson = argumentsJson;
            return "tc-1";
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, boolean success, String output, String error) {
            this.resultToolCallId = toolCallId;
            this.resultSuccess = success;
            this.resultOutput = output;
            this.resultError = error;
            assertThat(toolName).isEqualTo(invokedToolName);
            assertThat(resultToolCallId).isEqualTo("tc-1");
        }
    }

    private record FakeToolProvider(String supportedTool, ToolExecutionResult result) implements ToolProvider {

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public List<ToolDescriptor> listTools() {
            return List.of(new ToolDescriptor(supportedTool, "fake", "none", "LOW"));
        }

        @Override
        public boolean supports(String toolName) {
            return supportedTool.equals(toolName);
        }

        @Override
        public ToolExecutionResult execute(String toolName, String argumentsJson) {
            return result;
        }
    }
}
