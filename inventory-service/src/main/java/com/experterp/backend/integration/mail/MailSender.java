package com.experterp.backend.integration.mail;

/**
 * Phase M1: sends an e-mail. {@code APP_MAIL_MODE=fake} (the default) keeps it in the outbox without sending;
 * {@code live} sends it by the organisation's SMTP server.
 */
public interface MailSender {

    /** fake or live */
    String mode();

    /** An e-mail with one optional PDF attachment, from the organisation's address. */
    record Mail(String fromName, String fromAddress, String replyTo, String to, String cc, String subject, String body,
                String attachmentName, byte[] attachment) {}

    /** The organisation's SMTP server (password already decrypted). */
    record Smtp(String host, int port, String username, String password, boolean startTls) {}

    /** Sends it (live) or does nothing (fake: the outbox row is the result). Throws IllegalStateException when it cannot. */
    void send(Mail mail, Smtp smtp);
}
