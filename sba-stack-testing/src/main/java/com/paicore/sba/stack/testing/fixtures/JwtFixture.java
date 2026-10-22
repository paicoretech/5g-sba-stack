package com.paicore.sba.stack.testing.fixtures;

import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues HS256 JWTs that the shared-secret {@link JwtAuthenticator} accepts.
 * Mirrors the bytes-padding logic of
 * {@link JwtAuthenticator#withSharedSecret(String, String)} so a token issued
 * here will round-trip through the security kit with the same secret value.
 */
public final class JwtFixture {

    public static final String DEFAULT_SECRET = "sba-stack-testing-shared-secret";
    public static final String DEFAULT_ISSUER = "https://nrf.local/oauth2";

    private static final JwtFixture SHARED = new JwtFixture(DEFAULT_SECRET, DEFAULT_ISSUER);

    private final String secret;
    private final String issuer;
    private final SecretKey signingKey;

    private JwtFixture(String secret, String issuer) {
        this.secret = secret;
        this.issuer = issuer;
        this.signingKey = paddedKey(secret);
    }

    public static JwtFixture shared() {
        return SHARED;
    }

    public static JwtFixture of(String secret, String issuer) {
        return new JwtFixture(secret, issuer);
    }

    public String secret() {
        return secret;
    }

    public String issuer() {
        return issuer;
    }

    public JwtAuthenticator authenticator() {
        return JwtAuthenticator.withSharedSecret(secret, issuer);
    }

    public String issueToken(String subject, Duration validity, String... scopes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .claim("scope", String.join(" ", scopes == null ? new String[]{} : scopes))
                .signWith(signingKey)
                .compact();
    }

    public String issueExpiredToken(String subject) {
        Instant now = Instant.now().minusSeconds(120);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuer(issuer)
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now))
                .signWith(signingKey)
                .compact();
    }

    public String issueTokenWithIssuer(String subject, String customIssuer) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuer(customIssuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns a minimal RFC 7517 JWKS document with the (symmetric) signing
     * key exposed as an {@code oct} JWK so the mock {@code /oauth2/jwks}
     * endpoint can publish something correlate-able to the active secret.
     */
    public Map<String, Object> jwks() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "oct");
        jwk.put("alg", "HS256");
        jwk.put("use", "sig");
        jwk.put("kid", "sba-stack-testing");
        jwk.put("k", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(paddedSecretBytes(secret)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("keys", List.of(jwk));
        return root;
    }

    private static SecretKey paddedKey(String secret) {
        return new SecretKeySpec(paddedSecretBytes(secret), "HmacSHA256");
    }

    private static byte[] paddedSecretBytes(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) {
            return bytes;
        }
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }
}
