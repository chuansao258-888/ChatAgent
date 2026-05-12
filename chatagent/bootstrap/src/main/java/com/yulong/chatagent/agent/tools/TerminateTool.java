package com.yulong.chatagent.agent.tools;

// 当前暂时禁用：模型曾经倾向于过早调用 terminate，而不是继续回答用户或调用正确工具。
// Agent 循环已经可以在“模型输出不含 tool_call”时自然结束，所以显式终止工具目前收益不大，
// 反而容易导致任务未完成就被提前截断。代码保留在这里，方便未来按配置重新开放。
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
