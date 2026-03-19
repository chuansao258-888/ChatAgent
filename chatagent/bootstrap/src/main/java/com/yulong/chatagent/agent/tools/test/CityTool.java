package com.yulong.chatagent.agent.tools.test;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import org.springframework.stereotype.Component;

/**
 * Small sample tool used for local agent-tool testing.
 */
@Component
public class CityTool implements Tool {
    @Override
    public String getName() {
        return "cityTool";
    }

    @Override
    public String getDescription() {
        return "Return the current city.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getCity", description = "Return the current city.")
    public String getCity() {
        return "Shenzhen";
    }
}
