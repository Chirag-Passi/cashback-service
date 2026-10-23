package com.js.motos.clientApp.mail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.js.motos.clientApp.config.AppProperties;

/**
 * SMTP-backed mail. Sends are {@code @Async} so a slow or flaky mail server never
 * stalls an API response — a failed notification email is logged, not surfaced.
 *
 * <p>OTP mail is the exception: {@code OtpService} calls it synchronously so the
 * user is not told "code sent" when it was not.
 */
@Service
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender sender;
    private final AppProperties properties;
    private final String fromAddress;

    public SmtpMailService(JavaMailSender sender,
                           AppProperties properties,
                           org.springframework.core.env.Environment environment) {
        this.sender = sender;
        this.properties = properties;
        String configured = environment.getProperty("spring.mail.username", "");
        this.fromAddress = configured.isBlank() ? properties.supportEmail() : configured;
    }

    @Override
    public void sendOtp(String toEmail, String code, int validityMinutes) {
        String brand = properties.brandName();
        send(toEmail, brand + " verification code: " + code, """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto">
                  <h2 style="color:#111">%s</h2>
                  <p style="font-size:15px;color:#333">Use this code to sign in:</p>
                  <p style="font-size:34px;font-weight:700;letter-spacing:8px;color:#111;margin:24px 0">%s</p>
                  <p style="font-size:14px;color:#555">It expires in %d minutes.</p>
                  <p style="font-size:13px;color:#888;margin-top:28px">
                    If you did not request this, you can safely ignore this email.
                    Never share this code with anyone &mdash; our staff will never ask for it.
                  </p>
                </div>
                """.formatted(brand, code, validityMinutes));
    }

    @Override
    @Async
    public void sendRedemptionPaid(String toEmail, String referenceNo, BigDecimal amount, String payoutTarget) {
        send(toEmail, "Your cashback of " + rupees(amount) + " has been paid", """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto">
                  <h2 style="color:#111">Cashback paid</h2>
                  <p style="font-size:15px;color:#333">
                    We have sent <strong>%s</strong> to <strong>%s</strong>.
                  </p>
                  <p style="font-size:14px;color:#555">Reference: %s</p>
                  <p style="font-size:13px;color:#888">Open the app to view the payment receipt.</p>
                </div>
                """.formatted(rupees(amount), payoutTarget, referenceNo));
    }

    @Override
    @Async
    public void sendRedemptionRejected(String toEmail, String referenceNo, String reason) {
        send(toEmail, "Update on your cashback request " + referenceNo, """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto">
                  <h2 style="color:#111">Cashback request declined</h2>
                  <p style="font-size:15px;color:#333">Request <strong>%s</strong> could not be approved.</p>
                  <p style="font-size:15px;color:#333">Reason: %s</p>
                  <p style="font-size:13px;color:#888">Contact %s if you believe this is a mistake.</p>
                </div>
                """.formatted(referenceNo, escape(reason), properties.supportEmail()));
    }

    @Override
    @Async
    public void sendPayoutAccountVerified(String toEmail, String accountLabel) {
        send(toEmail, "Your payout account is verified", """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto">
                  <h2 style="color:#111">Payout account verified</h2>
                  <p style="font-size:15px;color:#333"><strong>%s</strong> is now verified.
                     You can start scanning codes and receiving cashback.</p>
                </div>
                """.formatted(escape(accountLabel)));
    }

    @Override
    @Async
    public void sendPayoutAccountRejected(String toEmail, String accountLabel, String reason) {
        send(toEmail, "Action needed on your payout account", """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto">
                  <h2 style="color:#111">Payout account needs attention</h2>
                  <p style="font-size:15px;color:#333">We could not verify <strong>%s</strong>.</p>
                  <p style="font-size:15px;color:#333">Reason: %s</p>
                  <p style="font-size:13px;color:#888">Please correct the details in the app and resubmit.</p>
                </div>
                """.formatted(escape(accountLabel), escape(reason)));
    }

    private void send(String to, String subject, String html) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, properties.brandName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.debug("Sent '{}' to {}", subject, mask(to));
        } catch (Exception e) {
            log.error("Failed sending '{}' to {}", subject, mask(to), e);
            throw new MailDeliveryException("Unable to send email", e);
        }
    }

    private static String rupees(BigDecimal amount) {
        return "₹" + amount.stripTrailingZeros().toPlainString();
    }

    /** Minimal HTML escaping for admin-authored text echoed into emails. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Keeps full addresses out of logs. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}