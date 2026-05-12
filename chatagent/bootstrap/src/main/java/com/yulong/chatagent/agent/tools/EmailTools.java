package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.mail.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 可选邮件发送工具。
 * <p>
 * 工具方法只负责参数校验和提交异步发送，真正的邮件发送由 EmailService 在后台处理。
 */
@Slf4j
@Component
public class EmailTools implements Tool {

    private final EmailService emailService;

    public EmailTools(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public String getName() {
        return "emailTool";
    }

    @Override
    public String getDescription() {
        return "Send emails asynchronously to a target recipient.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 校验邮件参数并提交异步发送任务。
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "sendEmail",
            description = "Send an email asynchronously. Required arguments: to, subject, and content."
    )
    public String sendEmail(String to, String subject, String content) {
        // 工具描述不能替代后端校验；模型传入的参数始终按不可信输入处理。
        if (to == null || to.trim().isEmpty()) {
            return "Error: recipient email address cannot be empty.";
        }
        if (subject == null || subject.trim().isEmpty()) {
            return "Error: email subject cannot be empty.";
        }
        if (content == null || content.trim().isEmpty()) {
            return "Error: email content cannot be empty.";
        }
        if (!to.contains("@")) {
            return "Error: recipient email address format is invalid.";
        }

        emailService.sendEmailAsync(to.trim(), subject.trim(), content.trim());

        log.info("Email submitted for async delivery: recipient={}, subject={}", to, subject);
        return String.format(
                "Email has been submitted for delivery.%nRecipient: %s%nSubject: %s%nThe message is being sent asynchronously.",
                to,
                subject
        );
    }
}
