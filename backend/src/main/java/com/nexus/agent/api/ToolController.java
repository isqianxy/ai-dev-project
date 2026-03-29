package com.nexus.agent.api;

import com.nexus.agent.api.dto.InvokeToolRequest;
import com.nexus.agent.api.dto.InvokeToolResponse;
import com.nexus.agent.api.dto.ToolInfoResponse;
import com.nexus.agent.service.tool.ToolDefinition;
import com.nexus.agent.service.tool.ToolExecutionResult;
import com.nexus.agent.service.tool.ToolExecutionService;
import com.nexus.agent.service.tool.ToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;

    public ToolController(ToolRegistry toolRegistry, ToolExecutionService toolExecutionService) {
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
    }

    @GetMapping
    public List<ToolInfoResponse> listTools() {
        return toolRegistry.all().stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{toolName}/invoke")
    @ResponseStatus(HttpStatus.OK)
    public InvokeToolResponse invokeTool(
            @PathVariable String toolName,
            @RequestBody(required = false) InvokeToolRequest body
    ) {
        String args = body == null ? "{}" : body.argumentsJson();
        ToolExecutionResult result = toolExecutionService.execute(toolName, args);
        return new InvokeToolResponse(result.success(), result.toolName(), result.output(), result.error());
    }

    private ToolInfoResponse toResponse(ToolDefinition d) {
        String paramType = d.parameterType() == null ? "none" : d.parameterType().getSimpleName();
        return new ToolInfoResponse(d.name(), d.description(), paramType);
    }
}
