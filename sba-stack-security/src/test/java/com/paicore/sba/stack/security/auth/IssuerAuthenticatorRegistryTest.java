package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssuerAuthenticatorRegistryTest {

    private IssuerAuthenticatorRegistry registry;
    private Authenticator tenantA;
    private Authenticator tenantB;
    private RSAKey rsaKey;

    @BeforeEach
    void setUp() throws Exception {
        registry = new IssuerAuthenticatorRegistry();
        tenantA = mock(Authenticator.class);
        tenantB = mock(Authenticator.class);
        rsaKey = new RSAKeyGenerator(2048).keyID("registry-kid").generate();
        registry.register("https://issuer-a.local", tenantA);
        registry.register("https://issuer-b.local", tenantB);
    }

    @Test
    @DisplayName("routes token to the matching tenant authenticator")
    void routesByIssuer() throws Exception {
        Principal expected = Principal.builder().id("amf-1").issuer("https://issuer-a.local").build();
        when(tenantA.authenticate(anyString())).thenReturn(expected);

        String token = signedToken("https://issuer-a.local");
        Principal principal = registry.authenticate(token);

        assertThat(principal).isSameAs(expected);
        verify(tenantA).authenticate(token);
        verify(tenantB, never()).authenticate(anyString());
    }

    @Test
    @DisplayName("unknown issuer is rejected before signature verification")
    void rejectsUnknownIssuer() throws Exception {
        String token = signedToken("https://stranger.local");

        assertThatThrownBy(() -> registry.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("Unknown issuer"));
        verify(tenantA, never()).authenticate(anyString());
        verify(tenantB, never()).authenticate(anyString());
    }

    @Test
    @DisplayName("malformed token without iss claim is rejected")
    void rejectsTokenWithoutIssuer() {
        assertThatThrownBy(() -> registry.authenticate("garbage"))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("iss"));
    }

    @Test
    @DisplayName("forIssuer returns the registered authenticator")
    void forIssuerLookup() {
        assertThat(registry.forIssuer("https://issuer-a.local")).contains(tenantA);
        assertThat(registry.forIssuer("https://issuer-b.local")).contains(tenantB);
        assertThat(registry.forIssuer("https://nope.local")).isEmpty();
    }

    @Test
    @DisplayName("forIssuer handles null and blank")
    void forIssuerNullSafe() {
        assertThat(registry.forIssuer(null)).isEmpty();
        assertThat(registry.forIssuer("   ")).isEmpty();
    }

    @Test
    @DisplayName("unregister removes the mapping")
    void unregister() {
        registry.unregister("https://issuer-a.local");
        assertThat(registry.forIssuer("https://issuer-a.local")).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot returns an immutable copy of the registry")
    void snapshotIsImmutable() {
        var snapshot = registry.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThatThrownBy(() -> snapshot.put("x", tenantA))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("clear empties the registry")
    void clear() {
        registry.clear();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("re-register overrides previous entry for the same issuer")
    void reregisterOverrides() {
        Authenticator replacement = mock(Authenticator.class);
        registry.register("https://issuer-a.local", replacement);
        assertThat(registry.forIssuer("https://issuer-a.local")).contains(replacement);
    }

    @Test
    @DisplayName("null arguments to register are silently ignored")
    void registerNullSafe() {
        int before = registry.size();
        registry.register(null, tenantA);
        registry.register("https://x.local", null);
        registry.register("   ", tenantA);
        assertThat(registry.size()).isEqualTo(before);
    }

    private String signedToken(String issuer) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject("amf-1")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
