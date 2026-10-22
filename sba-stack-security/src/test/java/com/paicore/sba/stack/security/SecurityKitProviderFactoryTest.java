package com.paicore.sba.stack.security;

import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.security.config.SecuritySettings;
import com.paicore.sba.stack.spi.SecurityProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityKitProviderFactoryTest {

    private static final String SECRET = "this-shared-secret-is-32-bytes!!";
    private static final String ISSUER = "ops-team";

    private final SecurityKitProviderFactory factory = new SecurityKitProviderFactory();

    @Test
    @DisplayName("create returns null for null config")
    void returnsNullForNullConfig() {
        assertThat(factory.create(null)).isNull();
    }

    @Test
    @DisplayName("create returns null for an unknown SecurityConfig marker")
    void returnsNullForUnknownMarker() {
        SecurityConfig opaque = new SecurityConfig() {};
        assertThat(factory.create(opaque)).isNull();
    }

    @Test
    @DisplayName("create returns null when neither apiKey nor jwtSecret is configured")
    void returnsNullWhenNoCredentialConfigured() {
        SecuritySettings empty = SecuritySettings.builder().build();
        assertThat(factory.create(empty)).isNull();
    }

    @Test
    @DisplayName("apiKey-only settings produce a provider that validates the configured key")
    void apiKeySettingsAuthenticatesKey() {
        SecuritySettings settings = SecuritySettings.builder().apiKey("secret-key-42").build();

        SecurityProvider provider = factory.create(settings);

        assertThat(provider).isNotNull();
        assertThat(provider.authenticate("secret-key-42")).isTrue();
        assertThat(provider.authenticate("wrong-key")).isFalse();
    }

    @Test
    @DisplayName("jwtSecret takes precedence over apiKey and accepts properly signed tokens")
    void jwtSecretBuildsJwtAuthenticator() {
        SecuritySettings settings = SecuritySettings.builder()
                .apiKey("ignored-when-jwt-present")
                .jwtSecret(SECRET)
                .jwtIssuer(ISSUER)
                .build();
        SecurityProvider provider = factory.create(settings);

        String validToken = Jwts.builder()
                .issuer(ISSUER)
                .subject("smsf-01")
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();

        assertThat(provider).isNotNull();
        assertThat(provider.authenticate(validToken)).isTrue();
        // API key path is bypassed when jwtSecret is set.
        assertThat(provider.authenticate("ignored-when-jwt-present")).isFalse();
    }

    @Test
    @DisplayName("jwt provider rejects tokens with the wrong issuer")
    void jwtProviderRejectsBadIssuer() {
        SecuritySettings settings = SecuritySettings.builder()
                .jwtSecret(SECRET)
                .jwtIssuer(ISSUER)
                .build();
        SecurityProvider provider = factory.create(settings);

        String hostileToken = Jwts.builder()
                .issuer("attacker")
                .subject("rogue")
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();

        assertThat(provider.authenticate(hostileToken)).isFalse();
    }
}
