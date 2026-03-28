CREATE TABLE IF NOT EXISTS agent_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    chat_options JSONB NOT NULL DEFAULT '{}'::jsonb,
    intent_tree JSONB NOT NULL DEFAULT '[]'::jsonb,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_template_code
    ON agent_template(code);

INSERT INTO agent_template (
    code,
    name,
    description,
    system_prompt,
    model,
    allowed_tools,
    chat_options,
    intent_tree,
    built_in,
    created_at,
    updated_at
) VALUES
(
    'hr',
    'HR Assistant',
    'Employee policy and internal HR process assistant for leave, attendance, and onboarding questions.',
    'You are the company HR assistant. Answer policy and process questions clearly, cite enterprise knowledge when available, avoid inventing HR rules, and tell the user when a question must be escalated to a human HR partner.',
    'deepseek-chat',
    $json$["emailTool"]$json$::jsonb,
    '{"temperature":0.3,"topP":0.95,"messageLength":10,"tokenBudget":4000}'::jsonb,
    $json$[
      {"code":"hr-domain","nodeLevel":"DOMAIN","name":"HR","description":"Human resources knowledge and policies.","examples":["人事政策","HR 帮助"],"enabled":true,"sortOrder":0},
      {"code":"hr-leave","parentCode":"hr-domain","nodeLevel":"CATEGORY","name":"Leave & Attendance","description":"Leave, attendance, overtime and scheduling.","examples":["请假制度","考勤规则"],"enabled":true,"sortOrder":0},
      {"code":"hr-leave-policy","parentCode":"hr-leave","nodeLevel":"TOPIC","name":"Leave policy","description":"Annual leave, sick leave and policy lookup.","examples":["年假有几天","病假怎么请"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":0},
      {"code":"hr-overtime","parentCode":"hr-leave","nodeLevel":"TOPIC","name":"Overtime workflow","description":"Overtime application and approval process.","examples":["加班怎么申请","调休规则"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":1},
      {"code":"hr-escalation","parentCode":"hr-domain","nodeLevel":"CATEGORY","name":"Escalation","description":"Human hand-off guidance.","examples":["联系 HR","人工处理"],"enabled":true,"sortOrder":1},
      {"code":"hr-escalation-system","parentCode":"hr-escalation","nodeLevel":"TOPIC","name":"Human HR hand-off","description":"Tell the user when to contact HR directly.","examples":["需要人工处理","我要找 HR"],"intentKind":"SYSTEM","systemPromptOverride":"Explain briefly that questions involving personal records, salary disputes, contract amendments, or missing policy data should be escalated to a human HR partner. Keep the response short and action-oriented.","enabled":true,"sortOrder":0}
    ]$json$::jsonb,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'it-ops',
    'IT Ops Assistant',
    'Internal IT support assistant for access issues, device setup, and common troubleshooting.',
    'You are the company IT operations assistant. Help with access, device setup, and internal troubleshooting. Prefer deterministic steps, ask for missing technical details when necessary, and escalate security-sensitive incidents quickly.',
    'deepseek-chat',
    $json$["emailTool","dataBaseTool"]$json$::jsonb,
    '{"temperature":0.2,"topP":0.9,"messageLength":10,"tokenBudget":4000}'::jsonb,
    $json$[
      {"code":"it-domain","nodeLevel":"DOMAIN","name":"IT Operations","description":"IT access, device setup, and troubleshooting.","examples":["IT 帮助","系统问题"],"enabled":true,"sortOrder":0},
      {"code":"it-access","parentCode":"it-domain","nodeLevel":"CATEGORY","name":"Access & Accounts","description":"SSO, VPN, permissions and account recovery.","examples":["账号登录","VPN 权限"],"enabled":true,"sortOrder":0},
      {"code":"it-access-kb","parentCode":"it-access","nodeLevel":"TOPIC","name":"Account troubleshooting","description":"Password reset and access issue resolution.","examples":["登录不上","VPN 连不上"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":0},
      {"code":"it-device","parentCode":"it-domain","nodeLevel":"CATEGORY","name":"Devices & Setup","description":"Laptop setup, software installation and environment bootstrap.","examples":["新电脑配置","安装软件"],"enabled":true,"sortOrder":1},
      {"code":"it-device-kb","parentCode":"it-device","nodeLevel":"TOPIC","name":"Device setup guide","description":"Standard setup steps and software installation guidance.","examples":["电脑初始化","办公软件安装"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":0},
      {"code":"it-notice","parentCode":"it-domain","nodeLevel":"CATEGORY","name":"Notifications","description":"Operational broadcast and stakeholder communication.","examples":["通知团队","发送维护公告"],"enabled":true,"sortOrder":2},
      {"code":"it-notice-tool","parentCode":"it-notice","nodeLevel":"TOPIC","name":"Send IT notice","description":"Use optional tools to send a stakeholder notification.","examples":["给团队发维护邮件","通知用户系统恢复"],"intentKind":"TOOL","allowedTools":["emailTool"],"enabled":true,"sortOrder":0}
    ]$json$::jsonb,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'customer-service',
    'Customer Service Assistant',
    'Internal customer service assistant for policy lookup, response drafting, and escalation guidance.',
    'You are the company customer service assistant. Provide accurate policy-based answers, stay calm and concise, and clearly separate confirmed policy from suggestions. Escalate when the customer issue requires human review.',
    'deepseek-chat',
    $json$["emailTool"]$json$::jsonb,
    '{"temperature":0.4,"topP":0.95,"messageLength":10,"tokenBudget":4000}'::jsonb,
    $json$[
      {"code":"cs-domain","nodeLevel":"DOMAIN","name":"Customer Service","description":"Customer policy and support operations.","examples":["客服帮助","客户问题"],"enabled":true,"sortOrder":0},
      {"code":"cs-policy","parentCode":"cs-domain","nodeLevel":"CATEGORY","name":"Policy Lookup","description":"FAQ, returns, exchange and shipping policy.","examples":["退换货","物流政策"],"enabled":true,"sortOrder":0},
      {"code":"cs-policy-kb","parentCode":"cs-policy","nodeLevel":"TOPIC","name":"Customer policy answer","description":"Use knowledge to answer customer policy questions.","examples":["退货规则是什么","多久能退款"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":0},
      {"code":"cs-escalation","parentCode":"cs-domain","nodeLevel":"CATEGORY","name":"Escalation","description":"Cases that require manual review.","examples":["人工处理","投诉升级"],"enabled":true,"sortOrder":1},
      {"code":"cs-escalation-system","parentCode":"cs-escalation","nodeLevel":"TOPIC","name":"Manual escalation guidance","description":"Explain when a support issue must be escalated.","examples":["升级处理","人工客服"],"intentKind":"SYSTEM","systemPromptOverride":"State that refund exceptions, complaints requiring supervisor review, or missing order context should be escalated to a human support specialist. Keep the wording brief and customer-friendly.","enabled":true,"sortOrder":0}
    ]$json$::jsonb,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'data-analysis',
    'Data Analysis Assistant',
    'Internal analytics assistant for KPI explanations, metric definitions, and lightweight data lookup.',
    'You are the company data analysis assistant. Explain metrics carefully, distinguish between documented definitions and fresh calculations, and avoid making unsupported numerical claims.',
    'glm-4.6',
    $json$["dataBaseTool"]$json$::jsonb,
    '{"temperature":0.2,"topP":0.9,"messageLength":12,"tokenBudget":4500}'::jsonb,
    $json$[
      {"code":"da-domain","nodeLevel":"DOMAIN","name":"Data Analysis","description":"Metrics, dashboards and analysis workflows.","examples":["数据分析","指标解释"],"enabled":true,"sortOrder":0},
      {"code":"da-kpi","parentCode":"da-domain","nodeLevel":"CATEGORY","name":"Metric Definitions","description":"Business KPI definitions and dashboard semantics.","examples":["GMV 定义","转化率怎么计算"],"enabled":true,"sortOrder":0},
      {"code":"da-kpi-kb","parentCode":"da-kpi","nodeLevel":"TOPIC","name":"Metric definition lookup","description":"Use knowledge base definitions before giving explanations.","examples":["指标口径是什么","看板字段含义"],"intentKind":"KB","scopePolicy":"FALLBACK_ALLOWED","bindSelectedKnowledgeBases":true,"enabled":true,"sortOrder":0},
      {"code":"da-query","parentCode":"da-domain","nodeLevel":"CATEGORY","name":"Data Lookup","description":"Structured query and report support.","examples":["查一下数据","跑个查询"],"enabled":true,"sortOrder":1},
      {"code":"da-query-tool","parentCode":"da-query","nodeLevel":"TOPIC","name":"Run data lookup","description":"Use the database tool for read-only lookups when knowledge is insufficient.","examples":["帮我查昨日订单量","执行一个只读查询"],"intentKind":"TOOL","allowedTools":["dataBaseTool"],"enabled":true,"sortOrder":0}
    ]$json$::jsonb,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO NOTHING;
