package com.js.motos.clientApp.mail;

/** Raised when an email could not be handed to the SMTP server. */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}