package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.mail.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Optional tool for asynchronous email delivery.
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
     * Validates arguments and schedules an asynchronous email send.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "sendEmail",
            description = "Send an email asynchronously. Required arguments: to, subject, and content."
    )
    public String sendEmail(String to, String subject, String content) {
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
