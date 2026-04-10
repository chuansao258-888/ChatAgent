package com.yulong.chatagent.agent.tools;

// Temporarily disabled: model was calling terminate prematurely instead of
// responding to the user or invoking the relevant tool. The agent loop already
// terminates naturally when the model produces a response without tool calls,
// so this explicit exit path is redundant and error-prone.
//
// @Component
// public class TerminateTool implements Tool {
//
//     @Override
//     public String getName() {
//         return "terminate";
//     }
//
//     @Override
//     public String getDescription() {
//         return "Terminate the agent loop once the task is complete.";
//     }
//
//     @Override
//     public ToolType getType() {
//         return ToolType.FIXED;
//     }
//
//     @org.springframework.ai.tool.annotation.Tool(
//             name = "terminate",
//             description = "Call this tool when the current task is complete and the agent should stop."
//     )
//     public void terminate() {
//     }
// }
