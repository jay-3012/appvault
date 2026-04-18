package com.appvault.auth;

import com.appvault.auth.dto.*;
import com.appvault.auth.email.EmailService;
import com.appvault.domain.user.*;
import com.appvault.security.JwtService;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.jwt.refresh-token-expiry-days}")
    private int refreshTokenExpiryDays;

    @Value("${app.email.verification-expiry-hours}")
    private int verificationExpiryHours;

    @Transactional
    public String register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(Role.USER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        userRepository.save(user);

        // Create verification token
        String token = UUID.randomUUID().toString();
        EmailVerification verification = EmailVerification.builder()
                .user(user)
                .token(token)
                .expiresAt(OffsetDateTime.now().plusHours(verificationExpiryHours))
                .build();
        emailVerificationRepository.save(verification);

        // Send email async — does not block this thread
        emailService.sendVerificationEmail(user.getEmail(), token);

        log.info("User registered: {}", user.getEmail());
        return "Registration successful. Please check your email to verify your account.";
    }

    @Transactional
    public String verifyEmail(String token) {
        EmailVerification verification = emailVerificationRepository
                .findByToken(token)
                .orElseThrow(() ->
                        new IllegalArgumentException("Invalid or expired verification token"));

        if (verification.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Verification token has expired");
        }

        User user = verification.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        emailVerificationRepository.delete(verification);

        log.info("Email verified for user: {}", user.getEmail());
        return "Email verified successfully. You can now log in.";
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail().toLowerCase(),
                            request.getPassword()
                    )
            );
        } catch (Exception e) {
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Please verify your email before logging in");
        }

        String accessToken = jwtService.generateAccessToken(
                user.getEmail(),
                user.getRole().name(),
                user.getId().toString()
        );

        String refreshTokenValue = UUID.randomUUID().toString();
        String refreshTokenHash = hashToken(refreshTokenValue);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(refreshTokenHash)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTokenExpiryDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenValue)
                .role(user.getRole().name())
                .userId(user.getId().toString())
                .email(user.getEmail())
                .build();
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenValue) {
        String tokenHash = hashToken(refreshTokenValue);

        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() ->
                        new IllegalArgumentException("Invalid refresh token"));

        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new IllegalArgumentException("Refresh token has expired. Please log in again.");
        }

        User user = refreshToken.getUser();

        // Rotate: delete old, create new
        refreshTokenRepository.delete(refreshToken);

        String newAccessToken = jwtService.generateAccessToken(
                user.getEmail(),
                user.getRole().name(),
                user.getId().toString()
        );

        String newRefreshValue = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(newRefreshValue))
                .expiresAt(OffsetDateTime.now().plusDays(refreshTokenExpiryDays))
                .build();
        refreshTokenRepository.save(newRefreshToken);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshValue)
                .role(user.getRole().name())
                .userId(user.getId().toString())
                .email(user.getEmail())
                .build();
    }

    public void logout(String accessToken) {
        // Calculate remaining TTL for the token
        Date expiration = jwtService.extractExpiration(accessToken);
        long ttlMillis = expiration.getTime() - System.currentTimeMillis();

        if (ttlMillis > 0) {
            String blacklistKey = "blacklist:token:" + accessToken;
            redisTemplate.opsForValue().set(
                    blacklistKey,
                    "1",
                    ttlMillis,
                    TimeUnit.MILLISECONDS
            );
        }

        log.info("Token blacklisted on logout");
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Token hashing failed", e);
        }
    }
}
