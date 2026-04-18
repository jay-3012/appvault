package com.appvault.auth.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    @Value("${resend.api-key}")
    private String resendApiKey;

    @Value("${app.email.from}")
    private String fromEmail;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        String verifyUrl = frontendUrl + "/auth/verify-email?token=" + token;
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                  <h2>Verify your AppVault account</h2>
                  <p>Click the button below to verify your email address.</p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;background:#1D9E75;
                            color:white;text-decoration:none;border-radius:6px;">
                    Verify email
                  </a>
                  <p style="color:#666;font-size:12px;">
                    This link expires in 24 hours. If you did not create an account, ignore this email.
                  </p>
                </div>
                """.formatted(verifyUrl);

        sendEmail(toEmail, "Verify your AppVault account", html);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String token) {
        String resetUrl = frontendUrl + "/auth/reset-password?token=" + token;
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                  <h2>Reset your AppVault password</h2>
                  <p>Click the button below to reset your password.</p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;background:#1D9E75;
                            color:white;text-decoration:none;border-radius:6px;">
                    Reset password
                  </a>
                  <p style="color:#666;font-size:12px;">
                    This link expires in 1 hour. If you did not request this, ignore this email.
                  </p>
                </div>
                """.formatted(resetUrl);

        sendEmail(toEmail, "Reset your AppVault password", html);
    }

    private void sendEmail(String to, String subject, String html) {
        try {
            Resend resend = new Resend(resendApiKey.trim());

            CreateEmailOptions options = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(to)
                    .subject(subject)
                    .html(html)
                    .build();

            CreateEmailResponse response = resend.emails().send(options);
            log.info("Email sent triggered to {}: ID {}", to, response.getId());

        } catch (Exception e) {
            log.error("Email SDK exception sending to {}: {}", to, e.getMessage());
        }
    }
}
