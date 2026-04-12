package com.chatapp.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Base64;
import java.util.Set;

/**
 * Fail-fast configuration validation. Active on the {@code prod} profile.
 *
 * Catches the kind of "deployed with the dev default JWT secret" mistake that
 * silently turns a private chat into a public one.
 */
@Slf4j
@Configuration
public class SecretsValidator {

    /** Defaults from application.yml that MUST NOT appear in production. */
    private static final Set<String> DEV_DEFAULT_JWT_SECRETS = Set.of(
            "Y2hhdEFwcFNlY3JldEtleUZvckpXVFRva2VuU2lnbmluZ1RoaXNJc0FMb25nZXJLZXlGb3JTZWN1cml0eQ=="
    );

    private static final Set<String> DEV_DEFAULT_DB_PASSWORDS = Set.of(
            "pimao1011",
            "password",
            "root",
            ""
    );

    private final Environment environment;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${cors.allowed-origins:}")
    private String corsOrigins;

    @Value("${websocket.allowed-origins:}")
    private String wsOrigins;

    public SecretsValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                isProd = true;
                break;
            }
        }
        if (!isProd) {
            log.info("SecretsValidator: dev profile, skipping strict checks");
            return;
        }

        // 1. JWT secret must be set, not the dev default, and >= 32 raw bytes (HS512 needs 64)
        if (jwtSecret == null || jwtSecret.isBlank()) {
            fail("JWT_SECRET is not set");
        }
        if (DEV_DEFAULT_JWT_SECRETS.contains(jwtSecret)) {
            fail("JWT_SECRET is the bundled dev default — refusing to start in prod");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(jwtSecret);
            if (decoded.length < 32) {
                fail("JWT_SECRET decodes to " + decoded.length + " bytes; need >= 32");
            }
        } catch (IllegalArgumentException e) {
            fail("JWT_SECRET is not valid Base64");
        }

        // 2. DB password not the dev default
        if (DEV_DEFAULT_DB_PASSWORDS.contains(dbPassword)) {
            fail("spring.datasource.password is the dev default or empty — refusing to start in prod");
        }

        // 3. CORS / WS origins must be explicit (no wildcards)
        if (corsOrigins == null || corsOrigins.isBlank() || corsOrigins.contains("*")) {
            fail("CORS_ALLOWED_ORIGINS must be a comma-separated list with no wildcards in prod");
        }
        if (wsOrigins == null || wsOrigins.isBlank() || wsOrigins.contains("*")) {
            fail("WS_ALLOWED_ORIGINS must be a comma-separated list with no wildcards in prod");
        }

        log.info("SecretsValidator: prod profile passed");
    }

    private void fail(String message) {
        throw new IllegalStateException("Production startup blocked: " + message);
    }
}
