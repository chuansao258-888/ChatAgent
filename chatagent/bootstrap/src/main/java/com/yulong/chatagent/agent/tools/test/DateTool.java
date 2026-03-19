package com.yulong.chatagent.agent.tools.test;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Small sample tool used for local date testing.
 */
@Component
public class DateTool implements Tool {
    @Override
    public String getName() {
        return "dateTool";
    }

    @Override
    public String getDescription() {
        return "Return the current date.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "Return the current date.")
    public String getDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
