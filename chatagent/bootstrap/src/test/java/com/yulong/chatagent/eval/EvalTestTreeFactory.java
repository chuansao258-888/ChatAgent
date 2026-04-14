package com.yulong.chatagent.eval;

import com.yulong.chatagent.intent.application.IntentTreeSnapshot;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;
import java.util.Map;

/**
 * Factory that builds a rich enterprise intent tree snapshot for eval tests.
 *
 * Tree topology:
 * <pre>
 * Root (assistant-1)
 *   +-- DOMAIN: 人事制度 (domain-hr)
 *   |     +-- CATEGORY: 考勤管理 (cat-hr-attendance)
 *   |     |     +-- TOPIC: 年假政策 (topic-leave-annual, KB, kb-leave)
 *   |     |     +-- TOPIC: 请假流程 (topic-leave-process, KB, kb-leave-proc)
 *   |     |     +-- TOPIC: 加班制度 (topic-overtime, KB, kb-overtime)
 *   |     +-- CATEGORY: 薪酬福利 (cat-hr-compensation)
 *   |           +-- TOPIC: 工资发放 (topic-salary, KB, kb-salary)
 *   |           +-- TOPIC: 社保公积金 (topic-insurance, KB, kb-insurance)
 *   +-- DOMAIN: 财务制度 (domain-finance)
 *   |     +-- CATEGORY: 报销管理 (cat-finance-reimbursement)
 *   |     |     +-- TOPIC: 差旅报销 (topic-travel-reimbursement, KB, kb-travel)
 *   |     |     +-- TOPIC: 日常报销 (topic-daily-reimbursement, KB, kb-expense)
 *   |     +-- CATEGORY: 采购管理 (cat-finance-procurement)
 *   |           +-- TOPIC: 采购申请 (topic-procurement, KB, kb-procurement)
 *   +-- DOMAIN: IT服务 (domain-it)
 *   |     +-- TOPIC: VPN申请 (topic-vpn, TOOL, [vpnTool])
 *   |     +-- TOPIC: 权限申请 (topic-permission, TOOL, [permissionTool])
 *   |     +-- TOPIC: 公司信息 (topic-company-info, SYSTEM)
 *   +-- DOMAIN: 行政办公 (domain-admin)
 *         +-- TOPIC: 会议室预约 (topic-meeting-room, TOOL, [meetingRoomTool])
 *         +-- TOPIC: 邮件发送 (topic-email, TOOL, [emailTool])
 * </pre>
 */
public final class EvalTestTreeFactory {

    public static final String AGENT_ID = "assistant-1";

    private EvalTestTreeFactory() {}

    public static IntentTreeSnapshot buildEnterpriseTree() {
        List<IntentNodeDTO> nodes = List.of(
                // === DOMAIN: 人事制度 ===
                node("domain-hr", null, IntentNodeLevel.DOMAIN, "人事制度",
                        "人事制度管理，包括考勤、薪酬、福利等",
                        List.of("人事", "员工手册", "HR", "人事制度"), null, null, 0),

                // CATEGORY: 考勤管理
                node("cat-hr-attendance", "domain-hr", IntentNodeLevel.CATEGORY, "考勤管理",
                        "考勤与休假管理",
                        List.of("考勤", "打卡", "请假", "休假"), null, null, 0),
                node("topic-leave-annual", "cat-hr-attendance", IntentNodeLevel.TOPIC, "年假政策",
                        "年假天数、结转规则、申请条件",
                        List.of("年假", "年假政策", "年假天数", "结转"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 0),
                node("topic-leave-process", "cat-hr-attendance", IntentNodeLevel.TOPIC, "请假流程",
                        "请假申请、审批流程、附件要求",
                        List.of("请假", "请假流程", "请假申请", "病假", "事假"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 1),
                node("topic-overtime", "cat-hr-attendance", IntentNodeLevel.TOPIC, "加班制度",
                        "加班申请、加班费计算、调休规则",
                        List.of("加班", "加班费", "调休", "加班申请"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 2),

                // CATEGORY: 薪酬福利
                node("cat-hr-compensation", "domain-hr", IntentNodeLevel.CATEGORY, "薪酬福利",
                        "工资、社保、公积金等薪酬福利管理",
                        List.of("工资", "薪酬", "福利", "社保"), null, null, 1),
                node("topic-salary", "cat-hr-compensation", IntentNodeLevel.TOPIC, "工资发放",
                        "工资发放时间、工资条查询、个税计算",
                        List.of("工资", "工资条", "个税", "发薪"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 0),
                node("topic-insurance", "cat-hr-compensation", IntentNodeLevel.TOPIC, "社保公积金",
                        "社保缴纳、公积金比例、五险一金",
                        List.of("社保", "公积金", "五险一金", "医保", "养老保险"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 1),

                // === DOMAIN: 财务制度 ===
                node("domain-finance", null, IntentNodeLevel.DOMAIN, "财务制度",
                        "财务管理，包括报销、采购、预算等",
                        List.of("财务", "报销", "费用", "发票", "预算"), null, null, 1),

                // CATEGORY: 报销管理
                node("cat-finance-reimbursement", "domain-finance", IntentNodeLevel.CATEGORY, "报销管理",
                        "差旅报销和日常报销",
                        List.of("报销", "差旅", "费用报销"), null, null, 0),
                node("topic-travel-reimbursement", "cat-finance-reimbursement", IntentNodeLevel.TOPIC, "差旅报销",
                        "出差报销标准、审批流程、票据要求",
                        List.of("差旅", "出差报销", "差旅费", "出差补贴"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 0),
                node("topic-daily-reimbursement", "cat-finance-reimbursement", IntentNodeLevel.TOPIC, "日常报销",
                        "办公用品、交通费等日常费用报销",
                        List.of("日常报销", "办公用品", "交通费", "报销单", "打车"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 1),

                // CATEGORY: 采购管理
                node("cat-finance-procurement", "domain-finance", IntentNodeLevel.CATEGORY, "采购管理",
                        "采购申请与审批",
                        List.of("采购", "购买", "供应商"), null, null, 1),
                node("topic-procurement", "cat-finance-procurement", IntentNodeLevel.TOPIC, "采购申请",
                        "采购申请流程、审批层级、合同签订",
                        List.of("采购申请", "采购流程", "供应商管理"), IntentKind.KB, ScopePolicy.FALLBACK_ALLOWED, 0),

                // === DOMAIN: IT服务 ===
                node("domain-it", null, IntentNodeLevel.DOMAIN, "IT服务",
                        "IT技术支持与系统服务",
                        List.of("IT", "技术", "系统", "网络", "电脑"), null, null, 2),
                node("topic-vpn", "domain-it", IntentNodeLevel.TOPIC, "VPN申请",
                        "VPN连接申请与配置指导",
                        List.of("VPN", "远程连接", "VPN申请", "外网访问"), IntentKind.TOOL, ScopePolicy.STRICT, 0),
                node("topic-permission", "domain-it", IntentNodeLevel.TOPIC, "权限申请",
                        "系统权限、数据权限申请",
                        List.of("权限", "权限申请", "系统权限", "数据权限"), IntentKind.TOOL, ScopePolicy.STRICT, 1),
                node("topic-company-info", "domain-it", IntentNodeLevel.TOPIC, "公司信息",
                        "公司基本信息查询",
                        List.of("公司信息", "公司简介", "关于公司", "公司介绍"), IntentKind.SYSTEM, ScopePolicy.STRICT, 2),

                // === DOMAIN: 行政办公 ===
                node("domain-admin", null, IntentNodeLevel.DOMAIN, "行政办公",
                        "行政事务与办公服务",
                        List.of("行政", "办公", "会议室", "邮件"), null, null, 3),
                node("topic-meeting-room", "domain-admin", IntentNodeLevel.TOPIC, "会议室预约",
                        "会议室查询、预约、取消",
                        List.of("会议室", "预约", "会议", "订会议室"), IntentKind.TOOL, ScopePolicy.STRICT, 0),
                node("topic-email", "domain-admin", IntentNodeLevel.TOPIC, "邮件发送",
                        "代发邮件、邮件模板",
                        List.of("发邮件", "邮件", "邮件发送", "群发"), IntentKind.TOOL, ScopePolicy.STRICT, 1)
        );

        Map<String, List<String>> kbMapping = Map.ofEntries(
                Map.entry("topic-leave-annual", List.of("kb-leave")),
                Map.entry("topic-leave-process", List.of("kb-leave-proc")),
                Map.entry("topic-overtime", List.of("kb-overtime")),
                Map.entry("topic-salary", List.of("kb-salary")),
                Map.entry("topic-insurance", List.of("kb-insurance")),
                Map.entry("topic-travel-reimbursement", List.of("kb-travel")),
                Map.entry("topic-daily-reimbursement", List.of("kb-expense")),
                Map.entry("topic-procurement", List.of("kb-procurement"))
        );

        return new IntentTreeSnapshot(AGENT_ID, 1, nodes, kbMapping);
    }

    private static IntentNodeDTO node(String id, String parentId, IntentNodeLevel level,
                                       String name, String description, List<String> examples,
                                       IntentKind intentKind, ScopePolicy scopePolicy, int sortOrder) {
        return IntentNodeDTO.builder()
                .id(id)
                .agentId(AGENT_ID)
                .parentId(parentId)
                .version(1)
                .status(IntentNodeStatus.PUBLISHED)
                .nodeLevel(level)
                .name(name)
                .description(description)
                .examples(examples)
                .intentKind(intentKind)
                .scopePolicy(scopePolicy)
                .enabled(true)
                .sortOrder(sortOrder)
                .build();
    }
}
