package com.experterp.backend.integration.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase M1: the live sender (APP_MAIL_MODE=live) by the organisation's SMTP server. Sending SMTP needs a mail library
 * (Spring Boot's mail starter, Jakarta Mail): not added without the owner's OK (docs/TODO.md, Needs the owner's OK).
 * Until then it refuses clearly, and the fake mode keeps every e-mail in the outbox.
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "live")
public class SmtpMailSender implements MailSender {

    @Override
    public String mode() { return "live"; }

    @Override
    public void send(Mail mail, Smtp smtp) {
        if (smtp == null || smtp.host() == null || smtp.host().isBlank()) throw new IllegalStateException("Set up the SMTP server first (Settings → E-mail)");
        throw new IllegalStateException("Live e-mail needs the mail library (spring-boot-starter-mail), waiting for the owner's OK: the e-mail is kept in the outbox");
    }
}
