package com.yulong.chatagent.agent.tools;

import org.springframework.stereotype.Component;

/**
 * Fixed tool used to signal the agent loop that all work is complete.
 */
@Component
public class TerminateTool implements Tool {

    @Override
    public String getName() {
        return "terminate";
    }

    @Override
    public String getDescription() {
        return "Terminate the agent loop once the task is complete.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "terminate",
            description = "Call this tool when the current task is complete and the agent should stop."
    )
    public void terminate() {
    }
}
