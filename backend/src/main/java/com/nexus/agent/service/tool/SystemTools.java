package com.nexus.agent.service.tool;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class SystemTools {

    @AgentTool(name = "current_time", description = "获取当前系统时间（ISO-8601）")
    public String currentTime() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
