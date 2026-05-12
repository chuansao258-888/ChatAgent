package com.yulong.chatagent.agent.tools;

// 旧文件系统工具已停用。
//
// 这个工具没有注册成 Spring bean，当前生产工具集合不会包含它。
// 原实现包含 read/write/list/delete/createDirectory 等本地文件操作；
// 为了避免读 02-agent-runtime 主线时误判为可用工具，这里先只保留停用说明。
//
// 如果未来要重新开放文件系统能力，需要重新评估安全边界、路径白名单和用户授权流程，
// 再恢复 Tool 实现并显式注册为 Spring bean。
//
// public class FileSystemTools implements Tool {
//     ...
// }
