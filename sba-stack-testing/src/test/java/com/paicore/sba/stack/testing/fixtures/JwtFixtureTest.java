package com.paicore.sba.stack.testing.fixtures;

import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import com.paicore.sba.stack.security.auth.Principal;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtFixtureTest {

    @Test
    @DisplayName("shared() returns the singleton fixture with the default secret and issuer")
    void sharedSingletonExposesDefaults() {
        JwtFixture fixture = JwtFixture.shared();

        assertThat(fixture).isSameAs(JwtFixture.shared());
        assertThat(fixture.secret()).isEqualTo(JwtFixture.DEFAULT_SECRET);
        assertThat(fixture.issuer()).isEqualTo(JwtFixture.DEFAULT_ISSUER);
    }

    @Test
    @DisplayName("of() builds an independent fixture with a custom secret and issuer")
    void ofProducesCustomFixture() {
        JwtFixture custom = JwtFixture.of("a-long-enough-custom-secret-1234", "https://nrf.custom/oauth2");

        assertThat(custom).isNotSameAs(JwtFixture.shared());
        assertThat(custom.secret()).isEqualTo("a-long-enough-custom-secret-1234");
        assertThat(custom.issuer()).isEqualTo("https://nrf.custom/oauth2");
    }

    @Test
    @DisplayName("issueToken emits a JWT the shared-secret JwtAuthenticator accepts")
    void issuedTokenRoundTripsThroughAuthenticator() {
        JwtFixture fixture = JwtFixture.shared();
        String token = fixture.issueToken("smsf-app", Duration.ofMinutes(5), "smsf.mo", "smsf.mt");

        JwtAuthenticator authenticator = fixture.authenticator();
        assertThat(authenticator).isNotNull();
        Principal principal = authenticator.authenticate(token);
        assertThat(principal.getId()).isEqualTo("smsf-app");
        assertThat(principal.getScopes()).containsExactlyInAnyOrder("smsf.mo", "smsf.mt");
    }

    @Test
    @DisplayName("issueToken treats a null scopes array as no scopes")
    void issueTokenAcceptsNullScopes() {
        JwtFixture fixture = JwtFixture.shared();
        String token = fixture.issueToken("smsf-app", Duration.ofMinutes(1), (String[]) null);
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("issueExpiredToken yields a token the authenticator rejects as expired")
    void expiredTokenIsRejected() {
        JwtFixture fixture = JwtFixture.shared();
        String token = fixture.issueExpiredToken("smsf-app");
        JwtAuthenticator authenticator = fixture.authenticator();

        assertThat(token).isNotBlank();
        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("issueTokenWithIssuer overrides the issuer claim while keeping the signature valid")
    void customIssuerInToken() {
        JwtFixture fixture = JwtFixture.shared();
        String token = fixture.issueTokenWithIssuer("smsf-app", "https://other.nrf/oauth2");
        JwtAuthenticator authenticator = fixture.authenticator();

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("jwks() exposes a minimal RFC 7517 document with the symmetric key")
    void jwksDocumentExposesSigningKey() {
        Map<String, Object> jwks = JwtFixture.shared().jwks();

        assertThat(jwks).containsKey("keys");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(1);
        Map<String, Object> jwk = keys.get(0);
        assertThat(jwk)
                .containsEntry("kty", "oct")
                .containsEntry("alg", "HS256")
                .containsEntry("use", "sig")
                .containsEntry("kid", "sba-stack-testing");
        assertThat(jwk.get("k")).asString().isNotBlank();
        byte[] decoded = Base64.getUrlDecoder().decode((String) jwk.get("k"));
        // Padded to 32 bytes (256-bit) per the authenticator's HMAC requirements.
        assertThat(decoded).hasSize(32);
    }

    @Test
    @DisplayName("an already-long secret is not padded — the JWKS key matches the raw bytes")
    void longSecretIsNotPadded() {
        String longSecret = "this-secret-is-exactly-32-bytes!";
        JwtFixture fixture = JwtFixture.of(longSecret, "https://nrf.long/oauth2");

        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) fixture.jwks().get("keys")).get(0);
        byte[] decoded = Base64.getUrlDecoder().decode((String) jwk.get("k"));
        assertThat(decoded).hasSize(longSecret.length());
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected by the authenticator")
    void wrongKeyTokenRejected() {
        byte[] foreignKey = new byte[32];
        for (int i = 0; i < foreignKey.length; i++) foreignKey[i] = (byte) (i + 1);
        String foreign = Jwts.builder()
                .subject("attacker")
                .issuer(JwtFixture.DEFAULT_ISSUER)
                .signWith(new javax.crypto.spec.SecretKeySpec(foreignKey, "HmacSHA256"))
                .compact();

        JwtAuthenticator authenticator = JwtFixture.shared().authenticator();
        assertThatThrownBy(() -> authenticator.authenticate(foreign))
                .isInstanceOf(AuthenticationException.class);
    }
}
