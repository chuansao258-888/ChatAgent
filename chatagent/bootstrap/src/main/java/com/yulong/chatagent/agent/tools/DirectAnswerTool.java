package com.yulong.chatagent.agent.tools;

/**
 * Marker tool used when the model can answer directly without further tool calls.
 * <p>
 * This tool is intentionally not registered as a Spring bean right now.
 */
public class DirectAnswerTool implements Tool {

    @Override
    public String getName() {
        return "directAnswer";
    }

    @Override
    public String getDescription() {
        return "Use this tool when the user's request can be answered directly without any additional action.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "directAnswer",
            description = "Use this tool when the model should respond directly to the user without calling any other tool."
    )
    public void directAnswer() {
    }
}
