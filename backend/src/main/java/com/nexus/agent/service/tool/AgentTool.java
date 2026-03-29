package com.nexus.agent.service.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    String name();

    String description();

    ToolRiskLevel riskLevel() default ToolRiskLevel.LOW;
}
