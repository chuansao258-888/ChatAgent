package com.yulong.chatagent.agent.tools;

// 旧 directAnswer 工具已停用。
//
// 当前 Agent runtime 已经可以通过“模型响应不含 tool_call”自然结束循环，
// 不需要再暴露一个显式 directAnswer tool。保留这个文件说明历史用途，
// 避免阅读工具集合时误以为它仍会进入 AgentToolCallbackFactory。
//
// public class DirectAnswerTool implements Tool {
//     ...
// }
