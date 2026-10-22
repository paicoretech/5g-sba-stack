package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticatorTest {

    private static final String SECRET = "this-is-a-shared-secret-32bytes!";
    private static final SecretKey KEY = new SecretKeySpec(
            SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private String mint(String issuer, String subject, String scope) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .claim("scope", scope)
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(KEY)
                .compact();
    }

    @Test
    @DisplayName("authenticate returns a Principal carrying subject, issuer and scopes")
    void acceptsValidToken() {
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, "smsf-issuer");
        Principal principal = auth.authenticate(mint("smsf-issuer", "user-42", "smsf.mt smsf.mo"));
        assertEquals("user-42", principal.getId());
        assertEquals("smsf-issuer", principal.getIssuer());
        assertTrue(principal.getScopes().contains("smsf.mt"));
        assertTrue(principal.getScopes().contains("smsf.mo"));
    }

    @Test
    @DisplayName("authenticate rejects tokens whose issuer differs from the configured one")
    void rejectsWrongIssuer() {
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, "expected");
        String token = mint("attacker", "u", "");
        assertThrows(AuthenticationException.class, () -> auth.authenticate(token));
    }

    @Test
    @DisplayName("authenticate rejects tokens signed with a different secret")
    void rejectsTokenSignedWithDifferentKey() {
        String token = Jwts.builder()
                .subject("u")
                .signWith(new SecretKeySpec("another-secret-still-32-bytes-long!".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"))
                .compact();
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, null);
        assertThrows(AuthenticationException.class, () -> auth.authenticate(token));
    }

    @Test
    @DisplayName("authenticate rejects blank tokens with AuthenticationException")
    void rejectsBlankToken() {
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, null);
        assertThrows(AuthenticationException.class, () -> auth.authenticate(""));
    }

    @Test
    @DisplayName("authenticate rejects null tokens with AuthenticationException")
    void rejectsNullToken() {
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, null);
        assertThrows(AuthenticationException.class, () -> auth.authenticate(null));
    }

    @Test
    @DisplayName("authenticate yields an empty scope set when the token has no scope claim")
    void scopesAreEmptyWhenClaimIsMissing() {
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret(SECRET, null);
        String tokenNoScope = Jwts.builder()
                .issuer("issuer-x")
                .subject("user-7")
                .signWith(KEY)
                .compact();
        Principal principal = auth.authenticate(tokenNoScope);
        assertNotNull(principal.getScopes());
        assertTrue(principal.getScopes().isEmpty());
        assertEquals("user-7", principal.getId());
    }

    @Test
    @DisplayName("withSharedSecret pads secrets shorter than 32 bytes so HS256 stays usable")
    void factoryPadsShortSecretToMinimumLength() {
        // Shorter than 32 bytes — exercises the zero-padding branch.
        JwtAuthenticator auth = JwtAuthenticator.withSharedSecret("short-secret", null);
        // Verify the authenticator is usable: build a token with the SAME padded key
        byte[] padded = new byte[32];
        byte[] src = "short-secret".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, padded, 0, src.length);
        String token = Jwts.builder()
                .subject("padded-user")
                .signWith(new SecretKeySpec(padded, "HmacSHA256"))
                .compact();
        Principal principal = auth.authenticate(token);
        assertEquals("padded-user", principal.getId());
    }
}
