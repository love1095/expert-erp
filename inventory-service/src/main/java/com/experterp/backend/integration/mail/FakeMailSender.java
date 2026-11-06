package com.experterp.backend.integration.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Phase M1: nothing leaves the server; the e-mail is kept in the outbox, where it can be read and its PDF opened. */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "fake", matchIfMissing = true)
public class FakeMailSender implements MailSender {

    @Override
    public String mode() { return "fake"; }

    @Override
    public void send(Mail mail, Smtp smtp) {
        if (mail.to() == null || mail.to().isBlank()) throw new IllegalStateException("No recipient");
    }
}
