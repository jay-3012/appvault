package com.appvault.auth.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        // Email sending not configured yet.
        // Token is returned directly in the register API response.
        log.info("VERIFY TOKEN for {}: {}", toEmail, token);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String token) {
        log.info("RESET TOKEN for {}: {}", toEmail, token);
    }
}
