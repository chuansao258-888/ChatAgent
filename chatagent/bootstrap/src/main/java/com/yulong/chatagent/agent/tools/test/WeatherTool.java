package com.yulong.chatagent.agent.tools.test;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.tools.ToolType;
import org.springframework.stereotype.Component;

/**
 * Small sample weather tool used for local agent-tool testing.
 */
@Component
public class WeatherTool implements Tool {

    @Override
    public String getName() {
        return "weatherTool";
    }

    @Override
    public String getDescription() {
        return "Return a mock weather report.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "weather",
            description = "Return a mock weather report for the given city and date."
    )
    public String getWeather(String city, String date) {
        return city + " " + date + " weather result: sunny to partly cloudy, temperature 25C, humidity 60%.";
    }
}
