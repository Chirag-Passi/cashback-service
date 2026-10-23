package com.js.motos.clientApp.mail;

import java.math.BigDecimal;

/** Outbound transactional email. */
public interface MailService {

    void sendOtp(String toEmail, String code, int validityMinutes);

    void sendRedemptionPaid(String toEmail, String referenceNo, BigDecimal amount, String payoutTarget);

    void sendRedemptionRejected(String toEmail, String referenceNo, String reason);

    void sendPayoutAccountVerified(String toEmail, String accountLabel);

    void sendPayoutAccountRejected(String toEmail, String accountLabel, String reason);
}