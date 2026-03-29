package com.nexus.agent.service.tool;

import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : beans.values()) {
            Class<?> clazz = bean.getClass();
            for (Method method : clazz.getMethods()) {
                AgentTool ann = method.getAnnotation(AgentTool.class);
                if (ann == null) {
                    continue;
                }
                if (method.getParameterCount() > 1) {
                    throw new IllegalStateException("工具方法仅支持 0 或 1 个参数: " + ann.name());
                }
                Class<?> parameterType = method.getParameterCount() == 0 ? null : method.getParameterTypes()[0];
                tools.put(
                        ann.name(),
                        new ToolDefinition(ann.name(), ann.description(), ann.riskLevel(), bean, method, parameterType)
                );
            }
        }
    }

    public Optional<ToolDefinition> find(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    public Collection<ToolDefinition> all() {
        return tools.values();
    }
}
