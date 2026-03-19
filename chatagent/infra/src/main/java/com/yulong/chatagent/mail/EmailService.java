package com.yulong.chatagent.mail;

/**
 * Sends transactional emails for the application.
 */
public interface EmailService {

    /**
     * Sends an email asynchronously.
     *
     * @param to recipient email address
     * @param subject email subject
     * @param content email body
     */
    void sendEmailAsync(String to, String subject, String content);
}
