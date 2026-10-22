package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsymmetricJwtAuthenticatorTest {
    private static final URI JWKS_URI = URI.create("https://issuer.local/.well-known/jwks.json");
    private static final String ISSUER = "https://issuer.local";

    private RSAKey rsaKey;
    private ECKey ecKey;
    private JwksKeyStore jwksKeyStore;
    private AsymmetricJwtAuthenticator authenticator;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("rsa-kid").generate();
        ecKey = new ECKeyGenerator(Curve.P_256).keyID("ec-kid").generate();
        JWKSet set = new JWKSet(List.<JWK>of(rsaKey.toPublicJWK(), ecKey.toPublicJWK()));

        jwksKeyStore = Mockito.mock(JwksKeyStore.class);
        Mockito.when(jwksKeyStore.get()).thenReturn(set);

        authenticator = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .allowedAlgorithms(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256))
                .clockSkew(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("verifies RS256 token and returns Principal with sub/iss/scopes")
    void verifiesRs256() throws Exception {
        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        Principal principal = authenticator.authenticate(token);

        assertThat(principal.getId()).isEqualTo("amf-001");
        assertThat(principal.getIssuer()).isEqualTo(ISSUER);
        assertThat(principal.getScopes()).containsExactly("nsmsf-sms");
    }

    @Test
    @DisplayName("verifies ES256 token signed with the EC JWK")
    void verifiesEs256() throws Exception {
        String token = es256Token(ISSUER, "amf-001", "nsmsf-sms");

        Principal principal = authenticator.authenticate(token);

        assertThat(principal.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("rejects token signed with an algorithm not on the allow list")
    void rejectsDisallowedAlgorithm() throws Exception {
        AsymmetricJwtAuthenticator narrow = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .allowedAlgorithms(Set.of(JWSAlgorithm.ES256))
                .build();

        String rs256Token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        assertThatThrownBy(() -> narrow.authenticate(rs256Token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("not allowed"));
    }

    @Test
    @DisplayName("rejects token whose iss differs from expected")
    void rejectsBadIssuer() throws Exception {
        String token = rs256Token("https://other-issuer.local", "amf-001", "nsmsf-sms");

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("Issuer mismatch"));
    }

    @Test
    @DisplayName("rejects token whose aud does not include expected audience")
    void rejectsBadAudience() throws Exception {
        String token = signRs256(claims(ISSUER, "amf-001", "nsmsf-sms")
                .audience("other-svc")
                .build());

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("Audience"));
    }

    @Test
    @DisplayName("rejects expired token (with clock-skew applied)")
    void rejectsExpired() throws Exception {
        Instant past = Instant.now().minusSeconds(600);
        String token = signRs256(new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject("amf-001")
                .audience("nsmsf-sms")
                .claim("scope", "nsmsf-sms")
                .issueTime(Date.from(past.minusSeconds(60)))
                .expirationTime(Date.from(past))
                .build());

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("expired"));
    }

    @Test
    @DisplayName("rejects token whose scope claim does not include the required scope")
    void rejectsBadScope() throws Exception {
        String token = rs256Token(ISSUER, "amf-001", "other-scope");

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("scope does not include"));
    }

    @Test
    @DisplayName("rejects unsigned token")
    void rejectsUnsignedToken() {
        assertThatThrownBy(() -> authenticator.authenticate("not.a.jwt"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("rejects blank or null token")
    void rejectsBlank() {
        assertThatThrownBy(() -> authenticator.authenticate(""))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("Missing"));
        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("Missing"));
    }

    @Test
    @DisplayName("forces JWKS refresh when kid is not in the cached set")
    void refreshesWhenKidUnknown() throws Exception {
        RSAKey freshKey = new RSAKeyGenerator(2048).keyID("rotated-kid").generate();
        Mockito.when(jwksKeyStore.refresh()).thenReturn(new JWKSet(List.<JWK>of(freshKey.toPublicJWK())));

        String token = signWith(freshKey,
                claims(ISSUER, "amf-001", "nsmsf-sms").audience("nsmsf-sms").build());

        Principal principal = authenticator.authenticate(token);

        assertThat(principal.getId()).isEqualTo("amf-001");
        Mockito.verify(jwksKeyStore).refresh();
    }

    @Test
    @DisplayName("peekIssuer returns the iss claim without verifying signature")
    void peekIssuerWithoutVerification() throws Exception {
        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");
        assertThat(AsymmetricJwtAuthenticator.peekIssuer(token)).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("peekIssuer tolerates malformed input")
    void peekIssuerTolerantOfGarbage() {
        assertThat(AsymmetricJwtAuthenticator.peekIssuer("not-a-jwt")).isNull();
        assertThat(AsymmetricJwtAuthenticator.peekIssuer(null)).isNull();
        assertThat(AsymmetricJwtAuthenticator.peekIssuer("   ")).isNull();
    }

    @Test
    @DisplayName("exposes jwksUri and expectedIssuer getters")
    void exposesGetters() {
        assertThat(authenticator.jwksUri()).isEqualTo(JWKS_URI);
        assertThat(authenticator.expectedIssuer()).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("skips issuer enforcement when expectedIssuer is null")
    void skipsIssuerWhenNotConfigured() throws Exception {
        AsymmetricJwtAuthenticator lax = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedAudience("nsmsf-sms")
                .build();

        Principal p = lax.authenticate(rs256Token("https://other.issuer", "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("skips audience enforcement when expectedAudience is null or blank")
    void skipsAudienceWhenNotConfigured() throws Exception {
        AsymmetricJwtAuthenticator lax = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("   ")
                .build();

        Principal p = lax.authenticate(rs256Token(ISSUER, "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("skips scope enforcement when requiredScopes is empty")
    void skipsScopesWhenNotRequired() throws Exception {
        AsymmetricJwtAuthenticator noScopeNeeded = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(null)
                .build();

        Principal p = noScopeNeeded.authenticate(rs256Token(ISSUER, "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("uses default algorithms when allowedAlgorithms is empty or null")
    void usesDefaultAlgorithmsWhenNotConfigured() throws Exception {
        AsymmetricJwtAuthenticator defaultAlgs = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .allowedAlgorithms(Set.of())
                .build();

        Principal p = defaultAlgs.authenticate(rs256Token(ISSUER, "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("falls back to zero clock skew when clockSkew(null) is supplied")
    void clockSkewNullFallsBackToZero() throws Exception {
        AsymmetricJwtAuthenticator strict = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .clockSkew(null)
                .build();

        Principal p = strict.authenticate(rs256Token(ISSUER, "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("clockSkewSeconds overload sets the skew directly")
    void clockSkewSecondsDirectSetter() throws Exception {
        AsymmetricJwtAuthenticator strict = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .clockSkewSeconds(45L)
                .build();

        Principal p = strict.authenticate(rs256Token(ISSUER, "amf-001", "nsmsf-sms"));

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("rejects token whose audience is empty/null")
    void rejectsMissingAudience() throws Exception {
        JWTClaimsSet noAud = claims(ISSUER, "amf-001", "nsmsf-sms").audience(List.of()).build();
        String token = signRs256(noAud);

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("Audience does not include"));
    }

    @Test
    @DisplayName("rejects token without exp claim")
    void rejectsTokenWithoutExpiration() throws Exception {
        JWTClaimsSet noExp = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("amf-001")
                .audience("nsmsf-sms")
                .claim("scope", "nsmsf-sms")
                .jwtID(UUID.randomUUID().toString())
                .build();
        String token = signRs256(noExp);

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("exp claim is missing"));
    }

    @Test
    @DisplayName("rejects token whose scope claim is missing or not a string")
    void rejectsTokenWithNonStringScopeClaim() throws Exception {
        JWTClaimsSet weirdScope = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("amf-001")
                .audience("nsmsf-sms")
                .claim("scope", 42)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        String token = signRs256(weirdScope);

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("scope does not include"));
    }

    @Test
    @DisplayName("rejects token with no kid when JWKS keys list is empty")
    void rejectsWhenJwkSetIsEmpty() throws Exception {
        Mockito.when(jwksKeyStore.get()).thenReturn(new JWKSet());
        Mockito.when(jwksKeyStore.refresh()).thenReturn(new JWKSet());

        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("Signing key not found"));
    }

    @Test
    @DisplayName("uses the first JWK when token has no kid header")
    void picksFirstKeyWhenNoKid() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                claims(ISSUER, "amf-001", "nsmsf-sms").build());
        jwt.sign(new RSASSASigner(rsaKey));
        Mockito.when(jwksKeyStore.get()).thenReturn(new JWKSet(List.<JWK>of(rsaKey.toPublicJWK())));

        Principal p = authenticator.authenticate(jwt.serialize());

        assertThat(p.getId()).isEqualTo("amf-001");
    }

    @Test
    @DisplayName("rejects token when JwksKeyStore returns a null set")
    void rejectsNullJwksFromStore() throws Exception {
        Mockito.when(jwksKeyStore.get()).thenReturn(null);
        Mockito.when(jwksKeyStore.refresh()).thenReturn(null);

        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("Signing key not found"));
    }

    @Test
    @DisplayName("the second call with the same token does not verify the signature again")
    void reusesTheVerifiedToken() throws Exception {
        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        authenticator.authenticate(token);
        Mockito.reset(jwksKeyStore);

        Principal principal = authenticator.authenticate(token);

        assertThat(principal.getId()).isEqualTo("amf-001");
        Mockito.verify(jwksKeyStore, Mockito.never()).get();
        Mockito.verify(jwksKeyStore, Mockito.never()).refresh();
    }

    @Test
    @DisplayName("a cached token is still rejected once it expires")
    void rejectsCachedTokenAfterExpiry() throws Exception {
        Instant now = Instant.now();
        String token = signRs256(new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject("amf-001")
                .audience("nsmsf-sms")
                .claim("scope", "nsmsf-sms")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(1)))
                .build());

        AsymmetricJwtAuthenticator noSkew = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .clockSkew(Duration.ZERO)
                .build();

        assertThat(noSkew.authenticate(token).getId()).isEqualTo("amf-001");

        Thread.sleep(1200L);

        assertThatThrownBy(() -> noSkew.authenticate(token))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails()).contains("expired"));
    }

    @Test
    @DisplayName("a tampered token is a cache miss and is rejected")
    void rejectsTamperedTokenDespiteCachedSibling() throws Exception {
        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");
        authenticator.authenticate(token);

        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> authenticator.authenticate(tampered))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("with the cache disabled every call verifies the signature again")
    void verifiesEveryCallWhenCacheDisabled() throws Exception {
        AsymmetricJwtAuthenticator uncached = AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(jwksKeyStore)
                .jwksUri(JWKS_URI)
                .expectedIssuer(ISSUER)
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .clockSkew(Duration.ofSeconds(30))
                .verificationCacheTtlSeconds(0)
                .build();
        String token = rs256Token(ISSUER, "amf-001", "nsmsf-sms");

        uncached.authenticate(token);
        uncached.authenticate(token);

        Mockito.verify(jwksKeyStore, Mockito.times(2)).get();
    }

    private JWTClaimsSet.Builder claims(String issuer, String subject, String scope) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject)
                .audience("nsmsf-sms")
                .claim("scope", scope)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private String rs256Token(String issuer, String subject, String scope) throws Exception {
        return signRs256(claims(issuer, subject, scope).build());
    }

    private String es256Token(String issuer, String subject, String scope) throws Exception {
        return signEs256(claims(issuer, subject, scope).build());
    }

    private String signRs256(JWTClaimsSet claims) throws Exception {
        return signWith(rsaKey, claims);
    }

    private String signEs256(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(ecKey.getKeyID()).build(),
                claims);
        jwt.sign(new ECDSASigner(ecKey));
        return jwt.serialize();
    }

    private String signWith(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
