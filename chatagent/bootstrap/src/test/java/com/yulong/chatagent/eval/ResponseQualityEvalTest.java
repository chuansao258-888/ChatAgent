package com.yulong.chatagent.eval;

import com.yulong.chatagent.chat.ChatModelRouter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM-as-judge response quality evaluation.
 *
 * <p>Given golden queries and synthetic context paragraphs (simulating perfect retrieval),
 * generates answers via DeepSeek and judges them on three dimensions:
 * <ul>
 *   <li>Faithfulness — does the answer stick to the provided context?</li>
 *   <li>Answer relevancy — does the answer address the question?</li>
 *   <li>Answer containment — does the answer include expected golden fragments?</li>
 * </ul>
 *
 * <p>Requires DeepSeek API key. Uses smoke mode by default (5 per category = 20 queries).
 * Full run (100 queries): {@code -Deval.smoke=false}
 *
 * <p>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-response-quality \
 *      -Dtest=ResponseQualityEvalTest
 */
@Tag("eval-response-quality")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseQualityEvalTest {

    @Autowired
    private ChatModelRouter chatModelRouter;

    private ChatClient chatClient;
    private List<RagGoldenEntry> goldenQueries;

    @BeforeAll
    void setUp() {
        chatClient = chatModelRouter.route("deepseek-chat");
        boolean smoke = !"false".equalsIgnoreCase(System.getProperty("eval.smoke", "true"));
        goldenQueries = smoke
                ? GoldenDatasetLoader.loadRagGoldenSmoke()
                : GoldenDatasetLoader.loadRagGolden();
        System.out.printf("=== Response Quality Eval: %d queries (smoke=%s) ===%n",
                goldenQueries.size(), smoke);
    }

    @Test
    void evaluateResponseQuality() throws Exception {
        List<CaseResult> results = new ArrayList<>();

        for (int i = 0; i < goldenQueries.size(); i++) {
            RagGoldenEntry q = goldenQueries.get(i);
            String context = buildContext(q);
            String answer = generateAnswer(context, q.query(), q.category());
            JudgeResult judge = judgeQuality(context, q.query(), answer);
            double containment = answerContainment(answer, q.expectedAnswerFragments());

            results.add(new CaseResult(q, answer, judge.faithfulness(), judge.answerRelevancy(),
                    containment));

            System.out.printf("  [%d/%d] %s faith=%.2f rel=%.2f contain=%.2f%n",
                    i + 1, goldenQueries.size(), q.id(),
                    judge.faithfulness(), judge.answerRelevancy(), containment);

            if ((i + 1) % 5 == 0) Thread.sleep(1000);
        }

        double avgFaithfulness = results.stream().mapToDouble(r -> r.faithfulness).average().orElse(0);
        double avgRelevancy = results.stream().mapToDouble(r -> r.answerRelevancy).average().orElse(0);
        double avgContainment = results.stream().mapToDouble(r -> r.containment).average().orElse(0);
        long fullContainCount = results.stream().filter(r -> r.containment >= 1.0).count();

        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(r -> r.query.category(), LinkedHashMap::new, Collectors.toList()))
                .forEach((cat, catResults) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("count", catResults.size());
                    m.put("avgFaithfulness", round4(catResults.stream().mapToDouble(r -> r.faithfulness).average().orElse(0)));
                    m.put("avgAnswerRelevancy", round4(catResults.stream().mapToDouble(r -> r.answerRelevancy).average().orElse(0)));
                    m.put("avgContainment", round4(catResults.stream().mapToDouble(r -> r.containment).average().orElse(0)));
                    m.put("fullContainRate", round4(catResults.stream().filter(r -> r.containment >= 1.0).count() / (double) catResults.size()));
                    byCategory.put(cat, m);
                });

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "response-quality-llm-judge");
        report.put("model", "deepseek-chat");
        report.put("casesEvaluated", results.size());
        report.put("avgFaithfulness", round4(avgFaithfulness));
        report.put("avgAnswerRelevancy", round4(avgRelevancy));
        report.put("avgContainment", round4(avgContainment));
        report.put("fullContainRate", round4(fullContainCount / (double) results.size()));
        report.put("byCategory", byCategory);
        report.put("perCase", results.stream().map(CaseResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("response-quality-eval", report);

        System.out.println("\n=== Response Quality Evaluation ===");
        System.out.printf("Queries:            %d%n", results.size());
        System.out.printf("Avg faithfulness:   %.4f%n", avgFaithfulness);
        System.out.printf("Avg relevancy:      %.4f%n", avgRelevancy);
        System.out.printf("Avg containment:    %.4f%n", avgContainment);
        System.out.printf("Full contain rate:  %.2f%% (%d/%d)%n",
                fullContainCount * 100.0 / results.size(), fullContainCount, results.size());
        System.out.println("\nPer-category:");
        byCategory.forEach((cat, m) -> System.out.printf("  %-12s — faith=%.2f, rel=%.2f, contain=%.2f, fullContain=%.0f%%%n",
                cat, m.get("avgFaithfulness"), m.get("avgAnswerRelevancy"),
                m.get("avgContainment"), (double) m.get("fullContainRate") * 100));
        System.out.println("\nReport: " + reportPath);

        assertThat(results).isNotEmpty();
        assertThat(avgFaithfulness).as("faithfulness should be reasonable").isGreaterThan(0.3);
        assertThat(avgRelevancy).as("relevancy should be reasonable").isGreaterThan(0.3);
    }

    private String generateAnswer(String context, String query, String category) {
        String instruction = switch (category) {
            case "multi-hop" -> "请综合多个文档中的信息进行推理回答。";
            case "comparison" -> "请对比分析相关政策或数据后回答。";
            case "temporal" -> "请注意时间节点和时效性信息，准确回答。";
            default -> "请基于上下文信息准确回答问题。";
        };
        String prompt = """
                %s
                仅使用以下上下文信息回答问题，不要编造内容。如果上下文中没有相关信息，请说明无法回答。

                上下文：
                %s

                问题：%s

                回答：""".formatted(instruction, context, query);
        return chatClient.prompt(prompt).call().content();
    }

    private JudgeResult judgeQuality(String context, String query, String answer) {
        String prompt = """
                You are an expert evaluator. Rate the answer on TWO metrics:

                1. Faithfulness (0.0-1.0): Does the answer use ONLY information from the context?
                   - 1.0: All claims are supported by the context
                   - 0.5: Mix of supported and unsupported claims
                   - 0.0: Answer fabricates facts not in the context

                2. Answer Relevancy (0.0-1.0): Does the answer address the question?
                   - 1.0: Directly and comprehensively answers the question
                   - 0.5: Partially addresses the question
                   - 0.0: Does not address the question at all

                Context:
                %s

                Question:
                %s

                Answer:
                %s

                Output ONLY two numbers separated by a comma: faithfulness,relevancy
                Example: 0.8,0.7""".formatted(context, query, answer);
        String response = chatClient.prompt(prompt).call().content();
        return parseJudgeResult(response);
    }

    private JudgeResult parseJudgeResult(String response) {
        if (response == null || response.isBlank()) return new JudgeResult(0.0, 0.0);
        try {
            String[] parts = response.trim().split("[,;]");
            if (parts.length >= 2) {
                return new JudgeResult(parseScore(parts[0]), parseScore(parts[1]));
            }
            double s = parseScore(response.trim());
            return new JudgeResult(s, s);
        } catch (Exception e) {
            return new JudgeResult(0.0, 0.0);
        }
    }

    private double parseScore(String text) {
        String cleaned = text.trim().replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty()) return 0.0;
        return Math.max(0.0, Math.min(1.0, Double.parseDouble(cleaned)));
    }

    private double answerContainment(String answer, List<String> expectedFragments) {
        if (expectedFragments == null || expectedFragments.isEmpty()) return 1.0;
        String lower = answer.toLowerCase();
        long hits = expectedFragments.stream()
                .filter(f -> lower.contains(f.toLowerCase()))
                .count();
        return (double) hits / expectedFragments.size();
    }

    private static String buildContext(RagGoldenEntry q) {
        StringBuilder sb = new StringBuilder();
        for (String docId : q.relevanceGrades().keySet()) {
            int grade = q.relevanceGrades().getOrDefault(docId, 0);
            if (grade <= 0) continue;
            String content = SYNTHETIC_CONTEXTS.getOrDefault(docId, "");
            if (!content.isEmpty()) {
                sb.append("【").append(docId).append("】\n").append(content).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private record JudgeResult(double faithfulness, double answerRelevancy) {}

    private record CaseResult(RagGoldenEntry query, String answer,
                              double faithfulness, double answerRelevancy, double containment) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", query.id());
            m.put("category", query.category());
            m.put("domain", query.domain());
            m.put("query", query.query());
            m.put("answer", answer);
            m.put("faithfulness", round4(faithfulness));
            m.put("answerRelevancy", round4(answerRelevancy));
            m.put("containment", round4(containment));
            m.put("expectedFragments", query.expectedAnswerFragments());
            return m;
        }
    }

    private static final Map<String, String> SYNTHETIC_CONTEXTS = Map.ofEntries(
            Map.entry("doc-leave-policy",
                    "年假政策：员工年假天数根据工龄确定。工龄1-5年为5天，5-10年为10天，10年以上为15天。" +
                    "年假最多可结转5天到下一年，超出部分作废。入职不满一年的员工按实际工作月数折算年假天数。" +
                    "年假需至少提前3个工作日提交申请。未休年假不予货币补偿，特殊情况需部门负责人和HR双重审批。"),
            Map.entry("doc-leave-process",
                    "请假流程：所有请假需通过OA系统提交申请。年假需提前3个工作日申请，病假需当日提交并附医院证明。" +
                    "3天以内由直属上级审批，3天以上需部门负责人审批，5天以上需HR总监审批。" +
                    "审批通过后系统自动扣减假期余额。紧急请假可先电话报备，48小时内补交申请。"),
            Map.entry("doc-overtime-policy",
                    "加班制度：工作日加班工资按1.5倍计算，周末加班按2倍计算，法定节假日加班按3倍计算。" +
                    "加班需提前在OA系统申请并获得上级审批。每月加班不得超过36小时。" +
                    "加班可选择调休或加班工资，调休需在3个月内使用完毕。晚间加班超过21:00可报销打车费。"),
            Map.entry("doc-salary-guide",
                    "薪资发放：每月15日发放上月工资，遇节假日提前至最近工作日。工资构成包括基本工资、绩效奖金和各项补贴。" +
                    "年终奖于次年1月发放，金额根据年度绩效考核结果确定，A级为3个月工资，B级为2个月，C级为1个月。" +
                    "试用期工资为正式工资的80%。工资条可在OA系统自助查询。"),
            Map.entry("doc-insurance-faq",
                    "社保公积金：公司为员工缴纳五险一金。养老保险个人缴纳8%、医疗保险个人缴纳2%、失业保险个人缴纳0.5%。" +
                    "住房公积金个人缴纳12%，公司等额匹配。生育保险和工伤保险由公司全额缴纳，个人无需缴费。" +
                    "生育保险报销需提供医院发票、出生证明和社保卡，报销比例最高100%，封顶线为当地社平工资3倍。" +
                    "公积金提取可用于购房、租房、装修等用途。"),
            Map.entry("doc-sick-leave-policy",
                    "病假政策：病假需提供医院出具的诊断证明和病假条。3天以内扣减当月全勤奖，3天以上按日扣减基本工资的30%。" +
                    "连续病假超过30天的，工资按当地最低工资标准的80%发放。年度累计病假超过90天需重新评估岗位适配性。"),
            Map.entry("doc-travel-reimbursement",
                    "差旅报销：出差需提前在OA系统提交出差申请。交通费按实报实销（经济舱/二等座标准）。" +
                    "住宿费标准：一线城市500元/晚，二线城市350元/晚，其他城市250元/晚。" +
                    "餐饮补贴100元/天，无需提供发票。出差结束后5个工作日内提交报销申请，逾期不予受理。" +
                    "报销审批流程：5000元以内部门经理审批，5000-20000元财务总监审批，20000元以上总经理审批。"),
            Map.entry("doc-daily-reimbursement",
                    "日常报销：办公用品采购500元以内可先购后报。报销需提供正规发票（增值税普通发票或专用发票）。" +
                    "报销单需填写费用类别、事由说明和审批人。电子发票需打印并附二维码验证结果。" +
                    "每月25日前提交当月报销，逾期计入下月处理。报销到账时间为审批通过后3个工作日。"),
            Map.entry("doc-invoice-policy",
                    "发票管理：公司仅接受增值税普通发票和增值税专用发票。发票抬头必须为公司全称，税号需完整准确。" +
                    "电子发票与纸质发票同等效力。发票金额需与报销金额一致，不得拆分或合并。" +
                    "虚假发票一经查实，按公司纪律处分条例处理。"),
            Map.entry("doc-procurement-guide",
                    "采购申请：5000元以上的采购需通过OA系统提交采购申请。申请需包含采购理由、预算金额和至少3家供应商报价。" +
                    "10000元以上需走招标流程。采购审批流程：部门负责人→财务部→采购部。" +
                    "紧急采购可走加急通道，但需事后补齐手续。采购到货后需办理入库手续并签收验收单。"),
            Map.entry("doc-vpn-guide",
                    "VPN申请：远程办公需申请VPN账号。通过IT服务台提交申请，需部门负责人审批。" +
                    "审批通过后1个工作日内开通。VPN支持Windows/Mac/iOS/Android平台。" +
                    "VPN账号有效期6个月，到期需续期。严禁通过VPN访问与工作无关的网站。" +
                    "连接问题请联系IT热线：400-xxx-xxxx。"),
            Map.entry("doc-permission-matrix",
                    "权限申请：系统权限按岗位角色分配。新员工入职时自动开通基础权限。" +
                    "额外权限需通过OA系统提交申请，经部门负责人和系统管理员审批。" +
                    "权限变更需48小时内生效。离职时所有权限自动回收。" +
                    "敏感数据访问权限需额外签署保密协议。权限审计每季度执行一次。"),
            Map.entry("doc-it-handbook",
                    "IT服务手册：公司提供标准办公设备（笔记本电脑、显示器、键鼠）。设备报修通过IT服务台提交工单。" +
                    "软件安装需经IT部门审批。公司WiFi密码每月更新，通过企业微信推送。" +
                    "数据备份由IT部门统一管理，重要文件建议存放在公司云盘。禁止安装未经授权的软件。"),
            Map.entry("doc-meeting-room-guide",
                    "会议室预约：通过OA系统预约会议室。会议室分为小型（6人）、中型（12人）和大型（30人）三类。" +
                    "预约需提前至少2小时。会议超时15分钟未到自动释放。" +
                    "大型会议室（30人以上）需部门负责人审批。视频会议设备使用需提前1天预约IT支持。"),
            Map.entry("doc-email-template",
                    "邮件发送规范：公司邮件需使用统一签名模板。外部邮件需抄送直属上级。" +
                    "附件大小限制为20MB，超大文件请使用公司云盘分享链接。" +
                    "群发邮件（超过50人）需经行政部审批。邮件保留期限为3年。" +
                    "紧急通知优先使用企业微信，邮件作为正式记录补发。")
    );
}
