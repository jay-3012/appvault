# Sprint 2 — Auth Module Implementation Guide

Complete step-by-step implementation of JWT authentication with three roles,
email verification, token refresh, and logout with Redis blacklisting.

Follow every section in order. Do not skip ahead.

---

## What you are building

| Endpoint | Description |
|---|---|
| `POST /auth/register` | Create account, send verification email |
| `POST /auth/verify-email` | Confirm email with token |
| `POST /auth/login` | Returns access token (15 min) + refresh token (7 days) |
| `POST /auth/refresh` | Rotate refresh token, issue new access token |
| `POST /auth/logout` | Blacklist access token in Redis |
| `GET /health` | Already done — now protected awareness |

Roles: `USER`, `DEVELOPER`, `ADMIN`

---

## Step 1 — Update pom.xml

Replace your current `pom.xml` entirely with this:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
  </parent>

  <groupId>com.appvault</groupId>
  <artifactId>appvault-backend</artifactId>
  <version>0.1.0</version>
  <name>appvault-backend</name>

  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>

  <dependencies>
    <!-- Web -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Security -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- JPA + PostgreSQL -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <!-- Redis -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- JWT -->
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>

    <!-- Flyway DB migrations -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

---

## Step 2 — Final file structure for Sprint 2

Before writing any code, create all these files/folders.
Everything lives under `backend/src/main/`:

```
backend/src/main/
├── java/com/appvault/
│   ├── AppVaultApplication.java          (already exists)
│   ├── HealthController.java             (already exists)
│   │
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   └── RedisConfig.java
│   │
│   ├── domain/
│   │   └── user/
│   │       ├── User.java                 (entity)
│   │       ├── Role.java                 (enum)
│   │       ├── UserStatus.java           (enum)
│   │       ├── UserRepository.java
│   │       ├── RefreshToken.java         (entity)
│   │       └── RefreshTokenRepository.java
│   │
│   ├── auth/
│   │   ├── AuthController.java
│   │   ├── AuthService.java
│   │   ├── dto/
│   │   │   ├── RegisterRequest.java
│   │   │   ├── LoginRequest.java
│   │   │   ├── AuthResponse.java
│   │   │   └── RefreshRequest.java
│   │   └── email/
│   │       └── EmailService.java
│   │
│   └── security/
│       ├── JwtService.java
│       ├── JwtAuthFilter.java
│       └── UserDetailsServiceImpl.java
│
└── resources/
    ├── application.yml
    └── db/migration/
        └── V1__create_users_table.sql
```

---

## Step 3 — Database migration

Create `backend/src/main/resources/db/migration/V1__create_users_table.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE user_role AS ENUM ('USER', 'DEVELOPER', 'ADMIN');
CREATE TYPE user_status AS ENUM ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED');

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            user_role    NOT NULL DEFAULT 'USER',
    status          user_status  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    full_name       VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);

CREATE TABLE email_verifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_verifications_token ON email_verifications(token);
```

---

## Step 4 — Update application.yml

Replace `backend/src/main/resources/application.yml` entirely:

```yaml
server:
  port: 8080

spring:
  application:
    name: appvault-backend

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/appvault}
    username: ${SPRING_DATASOURCE_USERNAME:appvault}
    password: ${SPRING_DATASOURCE_PASSWORD:changeme_local}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  flyway:
    enabled: true
    locations: classpath:db/migration

  data:
    redis:
      host: ${SPRING_REDIS_HOST:localhost}
      port: ${SPRING_REDIS_PORT:6379}
      timeout: 2000ms

app:
  jwt:
    secret: ${JWT_SECRET:local_dev_secret_min_32_chars_long_replace_in_prod}
    access-token-expiry-minutes: 15
    refresh-token-expiry-days: 7
  email:
    from: noreply@yourdomain.com
    verification-expiry-hours: 24
  frontend-url: ${FRONTEND_URL:http://localhost:4200}

resend:
  api-key: ${RESEND_API_KEY:re_local_dev_key}

gcp:
  project-id: ${GCP_PROJECT_ID:local}
```

---

## Step 5 — Enums

Create `backend/src/main/java/com/appvault/domain/user/Role.java`:

```java
package com.appvault.domain.user;

public enum Role {
    USER,
    DEVELOPER,
    ADMIN
}
```

Create `backend/src/main/java/com/appvault/domain/user/UserStatus.java`:

```java
package com.appvault.domain.user;

public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED
}
```

---

## Step 6 — User entity

Create `backend/src/main/java/com/appvault/domain/user/User.java`:

```java
package com.appvault.domain.user;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "user_role")
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "user_status")
    @Builder.Default
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.SUSPENDED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
}
```

---

## Step 7 — User and RefreshToken repositories

Create `backend/src/main/java/com/appvault/domain/user/UserRepository.java`:

```java
package com.appvault.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

Create `backend/src/main/java/com/appvault/domain/user/RefreshToken.java`:

```java
package com.appvault.domain.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
```

Create `backend/src/main/java/com/appvault/domain/user/RefreshTokenRepository.java`:

```java
package com.appvault.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user.id = :userId")
    void deleteAllByUserId(UUID userId);
}
```

---

## Step 8 — JWT service

Create `backend/src/main/java/com/appvault/security/JwtService.java`:

```java
package com.appvault.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.access-token-expiry-minutes}")
    private int accessTokenExpiryMinutes;

    public String generateAccessToken(String email, String role, String userId) {
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("userId", userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis()
                        + (long) accessTokenExpiryMinutes * 60 * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractUserId(String token) {
        return extractAllClaims(token).get("userId", String.class);
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder()
                        .encodeToString(jwtSecret.getBytes())
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
```

---

## Step 9 — JWT authentication filter

Create `backend/src/main/java/com/appvault/security/JwtAuthFilter.java`:

```java
package com.appvault.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        // Check Redis blacklist first
        String blacklistKey = "blacklist:token:" + jwt;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"Token has been invalidated\"}");
            return;
        }

        if (!jwtService.isTokenValid(jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String email = jwtService.extractEmail(jwt);

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
            authToken.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }
}
```

---

## Step 10 — UserDetailsService implementation

Create `backend/src/main/java/com/appvault/security/UserDetailsServiceImpl.java`:

```java
package com.appvault.security;

import com.appvault.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found: " + email));
    }
}
```

---

## Step 11 — Redis config

Create `backend/src/main/java/com/appvault/config/RedisConfig.java`:

```java
package com.appvault.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

---

## Step 12 — Security config

Create `backend/src/main/java/com/appvault/config/SecurityConfig.java`:

```java
package com.appvault.config;

import com.appvault.security.JwtAuthFilter;
import com.appvault.security.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(
                    "/health",
                    "/auth/register",
                    "/auth/verify-email",
                    "/auth/login",
                    "/auth/refresh"
                ).permitAll()
                // Admin only
                .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                // Developer only
                .requestMatchers("/developer/**")
                    .hasAnyRole("DEVELOPER", "ADMIN")
                // Everything else requires auth
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter,
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

---

## Step 13 — DTOs

Create `backend/src/main/java/com/appvault/auth/dto/RegisterRequest.java`:

```java
package com.appvault.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 100, message = "Full name must be 2–100 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
```

Create `backend/src/main/java/com/appvault/auth/dto/LoginRequest.java`:

```java
package com.appvault.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;
}
```

Create `backend/src/main/java/com/appvault/auth/dto/AuthResponse.java`:

```java
package com.appvault.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private String role;
    private String userId;
    private String email;
}
```

Create `backend/src/main/java/com/appvault/auth/dto/RefreshRequest.java`:

```java
package com.appvault.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
```

---

## Step 14 — Email service

Create `backend/src/main/java/com/appvault/auth/email/EmailService.java`:

```java
package com.appvault.auth.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
            String body = """
                    {
                      "from": "%s",
                      "to": ["%s"],
                      "subject": "%s",
                      "html": "%s"
                    }
                    """.formatted(
                    fromEmail,
                    to,
                    subject,
                    html.replace("\"", "\\\"").replace("\n", "")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Email sent to {}: {}", to, subject);
            } else {
                log.error("Email failed to {}: HTTP {} — {}",
                        to, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Email send exception to {}: {}", to, e.getMessage());
        }
    }
}
```

Also add `@EnableAsync` to your main application class:

Update `AppVaultApplication.java`:

```java
package com.appvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AppVaultApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppVaultApplication.class, args);
    }
}
```

---

## Step 15 — Auth service

Create `backend/src/main/java/com/appvault/auth/AuthService.java`:

```java
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
```

---

## Step 16 — EmailVerification entity and repository

Create `backend/src/main/java/com/appvault/domain/user/EmailVerification.java`:

```java
package com.appvault.domain.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
```

Create `backend/src/main/java/com/appvault/domain/user/EmailVerificationRepository.java`:

```java
package com.appvault.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
    Optional<EmailVerification> findByToken(String token);
}
```

---

## Step 17 — Auth controller

Create `backend/src/main/java/com/appvault/auth/AuthController.java`:

```java
package com.appvault.auth;

import com.appvault.auth.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest request) {

        String message = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", message));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @RequestParam String token) {

        String message = authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", message));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request) {

        AuthResponse response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.substring(7);
        authService.logout(token);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
```

---

## Step 18 — Global exception handler

Create `backend/src/main/java/com/appvault/config/GlobalExceptionHandler.java`:

```java
package com.appvault.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            fieldErrors.put(field, error.getDefaultMessage());
        });

        return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed", "fields", fieldErrors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(
            IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(
            BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid email or password"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred"));
    }
}
```

---

## Step 19 — Build and test locally

### Run locally with Docker (PostgreSQL + Redis must be running)

If you have Docker Desktop locally, start the dependencies:

```bash
cd backend

# Start just postgres and redis for local dev
docker run -d --name local-postgres \
  -e POSTGRES_DB=appvault \
  -e POSTGRES_USER=appvault \
  -e POSTGRES_PASSWORD=changeme_local \
  -p 5432:5432 \
  postgres:15-alpine

docker run -d --name local-redis \
  -p 6379:6379 \
  redis:7-alpine
```

Set environment variables and run:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/appvault
export SPRING_DATASOURCE_USERNAME=appvault
export SPRING_DATASOURCE_PASSWORD=changeme_local
export SPRING_REDIS_HOST=localhost
export SPRING_REDIS_PORT=6379
export JWT_SECRET=local_dev_secret_min_32_chars_long_replace_in_prod
export RESEND_API_KEY=re_local_dev_key
export GCP_PROJECT_ID=local
export FRONTEND_URL=http://localhost:4200

mvn spring-boot:run
```

You should see:
```
Tomcat started on port 8080
Started AppVaultApplication
```

### Test every endpoint with curl

```bash
# 1. Health check (still works)
curl http://localhost:8080/health

# 2. Register a new user
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Test User",
    "email": "test@example.com",
    "password": "password123"
  }'
# Expected: {"message":"Registration successful. Please check your email..."}

# 3. Try to login before email verification
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
# Expected: 403 {"error":"Please verify your email before logging in"}

# 4. Manually verify email (get token from DB for local testing)
# Run this in psql or via docker exec:
# SELECT token FROM email_verifications WHERE user_id = (SELECT id FROM users WHERE email = 'test@example.com');

curl "http://localhost:8080/auth/verify-email?token=TOKEN_FROM_DB"
# Expected: {"message":"Email verified successfully. You can now log in."}

# 5. Login — should now work
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
# Expected: {"accessToken":"eyJ...","refreshToken":"...","role":"USER",...}

# Save the tokens
ACCESS_TOKEN="eyJ..."
REFRESH_TOKEN="..."

# 6. Access a protected endpoint with token
curl http://localhost:8080/auth/me \
  -H "Authorization: Bearer $ACCESS_TOKEN"
# Expected: currently 404 (endpoint not built yet) — that is correct

# 7. Try protected endpoint WITHOUT token
curl http://localhost:8080/developer/apps
# Expected: 403 (no token)

# 8. Refresh the token
curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
# Expected: new accessToken and refreshToken

# 9. Logout
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN"
# Expected: {"message":"Logged out successfully"}

# 10. Try to use the old access token after logout
curl http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN"
# Expected: 401 {"error":"Token has been invalidated"}

# 11. Test validation — missing fields
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"bad","password":"short"}'
# Expected: 400 with field errors

# 12. Test wrong credentials
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"wrongpassword"}'
# Expected: 401 {"error":"Invalid email or password"}
```

All 12 tests must pass before committing.

---

## Step 20 — Update Docker Compose for secrets

On main-vm, the app container needs the DB password and JWT secret. Update the `docker-compose.yml` environment section for the `app` service:

```yaml
app:
  image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-backend:latest
  container_name: appvault-app
  restart: unless-stopped
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/appvault
    SPRING_DATASOURCE_USERNAME: appvault
    SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    SPRING_REDIS_HOST: redis
    SPRING_REDIS_PORT: 6379
    JWT_SECRET: ${JWT_SECRET}
    RESEND_API_KEY: ${RESEND_API_KEY}
    GCP_PROJECT_ID: ${GCP_PROJECT_ID}
    FRONTEND_URL: https://yourdomain.com
  ports:
    - "8080:8080"
```

Create a `.env` file on main-vm at `/home/ubuntu/appvault/.env` that pulls values from Secret Manager:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP

# Pull secrets and write to .env file (run this once after any secret rotation)
cat > /home/ubuntu/appvault/pull-secrets.sh << 'SCRIPT'
#!/bin/bash
DB_PASSWORD=$(gcloud secrets versions access latest --secret="db-password")
JWT_SECRET=$(gcloud secrets versions access latest --secret="jwt-secret")
RESEND_API_KEY=$(gcloud secrets versions access latest --secret="resend-api-key")
GCP_PROJECT_ID=$(gcloud config get-value project)

cat > /home/ubuntu/appvault/.env << EOF
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
RESEND_API_KEY=${RESEND_API_KEY}
GCP_PROJECT_ID=${GCP_PROJECT_ID}
EOF

chmod 600 /home/ubuntu/appvault/.env
echo "Secrets pulled to .env"
SCRIPT

chmod +x /home/ubuntu/appvault/pull-secrets.sh
./pull-secrets.sh
```

---

## Step 21 — Commit and deploy

```bash
# On your local machine
git add .
git commit -m "feat: Sprint 2 — JWT auth with register, verify, login, refresh, logout"
git push origin main
```

GitHub Actions will build and deploy automatically. Watch the Actions tab.

After deployment:

```bash
# Test the live endpoint
curl https://yourdomain.com/health
curl -X POST https://yourdomain.com/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test","email":"you@gmail.com","password":"password123"}'
```

---

## Sprint 2 definition of done checklist

Run through every item. All must pass.

```
[ ] POST /auth/register returns 201 with success message
[ ] Duplicate email registration returns 400
[ ] POST /auth/login before verification returns 403
[ ] GET /auth/verify-email with valid token returns 200 and activates account
[ ] GET /auth/verify-email with expired/invalid token returns 400
[ ] POST /auth/login with correct credentials returns accessToken + refreshToken
[ ] POST /auth/login with wrong password returns 401
[ ] POST /auth/refresh with valid refresh token returns new token pair
[ ] POST /auth/refresh with used/invalid token returns 400
[ ] POST /auth/logout blacklists the access token
[ ] Using blacklisted token returns 401
[ ] GET /developer/apps without token returns 403
[ ] GET /admin/anything without ADMIN role returns 403
[ ] Validation errors return 400 with field-level messages
[ ] App deploys to main-vm via GitHub Actions with zero errors
```

---

## Troubleshooting

### "Could not create Enum type"
Flyway migration may have partially run. Reset it:
```bash
docker exec -it appvault-postgres psql -U appvault -d appvault
DROP TABLE IF EXISTS flyway_schema_history;
DROP TYPE IF EXISTS user_role;
DROP TYPE IF EXISTS user_status;
\q
# Restart app — Flyway will re-run migrations
```

### "No bean named 'emailVerificationRepository'"
You have not created `EmailVerificationRepository.java`. Go back to Step 16.

### Redis connection refused
```bash
docker exec appvault-redis redis-cli ping
# If no response: docker compose restart redis
```

### JWT secret too short
The secret in `application.yml` must be at least 32 characters. In prod it comes from Secret Manager. Locally use any 32+ char string.

### Email not arriving locally
That is expected — `RESEND_API_KEY=re_local_dev_key` is a fake key. Check logs for the email that would have been sent:
```bash
# In Spring Boot logs you should see:
# Email send exception to test@example.com: ...
# This is correct behavior in local dev.
```
To actually test emails, sign up at resend.com, get a real API key, and use it in your local `.env`.