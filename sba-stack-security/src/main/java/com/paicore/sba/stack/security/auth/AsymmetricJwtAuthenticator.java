package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies asymmetrically-signed JWTs (RS256/ES256) against a JWK Set per
 * 3GPP TS 33.501 §13.4.1.1. Complements the HS256 {@link JwtAuthenticator}.
 *
 * <p>Requires {@code com.nimbusds:nimbus-jose-jwt} on the consumer classpath
 * (optional in this module).
 */
public final class AsymmetricJwtAuthenticator implements Authenticator {

    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE_SEPARATOR = " ";

    private final JwksKeyStore jwksKeyStore;
    private final URI jwksUri;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final Set<String> requiredScopes;
    private final Set<JWSAlgorithm> allowedAlgorithms;
    private final long clockSkewSeconds;
    private final VerifiedTokenCache verifiedTokenCache;

    private AsymmetricJwtAuthenticator(Builder builder) {
        this.jwksKeyStore = resolveJwksKeyStore(builder);
        this.jwksUri = Objects.requireNonNull(builder.jwksUri, "jwksUri");
        this.expectedIssuer = builder.expectedIssuer;
        this.expectedAudience = builder.expectedAudience;
        this.requiredScopes = Set.copyOf(builder.requiredScopes);
        this.allowedAlgorithms = Set.copyOf(builder.allowedAlgorithms);
        this.clockSkewSeconds = builder.clockSkewSeconds;
        this.verifiedTokenCache = new VerifiedTokenCache(
                builder.verificationCacheTtlSeconds, builder.verificationCacheMaxSize);
    }

    private static JwksKeyStore resolveJwksKeyStore(Builder builder) {
        if (Objects.nonNull(builder.jwksKeyStore)) {
            return builder.jwksKeyStore;
        }
        if (Objects.nonNull(builder.buildSettings)) {
            return JwksKeyStore.forHttp2(builder.buildSettings);
        }
        throw new IllegalStateException(
                "AsymmetricJwtAuthenticator requires either jwksKeyStore or http2BuildSettings");
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI jwksUri() {
        return jwksUri;
    }

    public String expectedIssuer() {
        return expectedIssuer;
    }

    @Override
    public Principal authenticate(String token) throws AuthenticationException {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("Missing JWT",
                    SecurityFailureCause.MISSING_AUTHORIZATION_HEADER);
        }
        String cacheKey = verifiedTokenCache.enabled() ? verifiedTokenCache.keyFor(token) : null;
        if (Objects.nonNull(cacheKey)) {
            VerifiedTokenCache.Entry cached = verifiedTokenCache.get(cacheKey);
            if (Objects.nonNull(cached)) {
                enforceExpiration(cached.expiration());
                return cached.principal();
            }
        }

        SignedJWT signed = parseSigned(token);
        JWSAlgorithm algorithm = signed.getHeader().getAlgorithm();
        if (algorithm == null || !allowedAlgorithms.contains(algorithm)) {
            throw new AuthenticationException("Signing algorithm not allowed: " + algorithm,
                    SecurityFailureCause.DISALLOWED_ALGORITHM);
        }

        String kid = signed.getHeader().getKeyID();
        JWK key = resolveKey(kid);
        verifySignature(signed, key);

        JWTClaimsSet claims = readClaims(signed);
        enforceIssuer(claims);
        enforceAudience(claims);
        enforceExpiration(claims);
        enforceScopes(claims);
        Principal principal = toPrincipal(claims);
        if (Objects.nonNull(cacheKey)) {
            verifiedTokenCache.put(cacheKey, principal, claims.getExpirationTime());
        }
        return principal;
    }

    private SignedJWT parseSigned(String token) {
        try {
            JWT parsed = JWTParser.parse(token);
            if (!(parsed instanceof SignedJWT signed)) {
                throw new AuthenticationException("Token is not a signed JWT",
                        SecurityFailureCause.INVALID_TOKEN);
            }
            return signed;
        } catch (ParseException ex) {
            throw new AuthenticationException("Token cannot be parsed",
                    SecurityFailureCause.INVALID_TOKEN, ex);
        }
    }

    private JWK resolveKey(String kid) {
        JWKSet set = jwksKeyStore.get();
        JWK key = lookupKey(set, kid);
        if (key != null) {
            return key;
        }
        JWKSet refreshed = jwksKeyStore.refresh();
        JWK retried = lookupKey(refreshed, kid);
        if (retried != null) {
            return retried;
        }
        throw new AuthenticationException("Signing key not found for kid=" + kid,
                SecurityFailureCause.INVALID_SIGNATURE);
    }

    private static JWK lookupKey(JWKSet set, String kid) {
        if (set == null) {
            return null;
        }
        if (kid != null && !kid.isBlank()) {
            return set.getKeyByKeyId(kid);
        }
        return set.getKeys().isEmpty() ? null : set.getKeys().get(0);
    }

    private static void verifySignature(SignedJWT signed, JWK key) {
        try {
            JWSVerifier verifier = switch (key) {
                case RSAKey rsa -> new RSASSAVerifier(rsa.toRSAPublicKey());
                case ECKey ec -> new ECDSAVerifier(ec.toECPublicKey());
                default -> throw new AuthenticationException("Unsupported signing key type: " + key.getKeyType(),
                        SecurityFailureCause.DISALLOWED_ALGORITHM);
            };
            if (!signed.verify(verifier)) {
                throw new AuthenticationException("Signature verification failed",
                        SecurityFailureCause.INVALID_SIGNATURE);
            }
        } catch (JOSEException ex) {
            throw new AuthenticationException("Failed to verify signature",
                    SecurityFailureCause.INVALID_SIGNATURE, ex);
        }
    }

    private JWTClaimsSet readClaims(SignedJWT signed) {
        try {
            return signed.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new AuthenticationException("Claims cannot be parsed",
                    SecurityFailureCause.INVALID_TOKEN, ex);
        }
    }

    private void enforceIssuer(JWTClaimsSet claims) {
        if (expectedIssuer == null) {
            return;
        }
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new AuthenticationException(
                    "Issuer mismatch (expected " + expectedIssuer + ", got " + claims.getIssuer() + ")",
                    SecurityFailureCause.ISSUER_MISMATCH);
        }
    }

    private void enforceAudience(JWTClaimsSet claims) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            return;
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
            throw new AuthenticationException("Audience does not include " + expectedAudience,
                    SecurityFailureCause.INVALID_TOKEN);
        }
    }

    private void enforceExpiration(JWTClaimsSet claims) {
        enforceExpiration(claims.getExpirationTime());
    }

    private void enforceExpiration(Date expiration) {
        if (expiration == null) {
            throw new AuthenticationException("exp claim is missing",
                    SecurityFailureCause.TOKEN_EXPIRED);
        }
        Instant cutoff = Instant.now().minusSeconds(clockSkewSeconds);
        if (expiration.toInstant().isBefore(cutoff)) {
            throw new AuthenticationException("Token expired at " + expiration.toInstant(),
                    SecurityFailureCause.TOKEN_EXPIRED);
        }
    }

    private void enforceScopes(JWTClaimsSet claims) {
        if (requiredScopes.isEmpty()) {
            return;
        }
        Set<String> granted = parseScopeClaim(claims);
        for (String required : requiredScopes) {
            if (!granted.contains(required)) {
                throw new AuthenticationException("scope does not include " + required,
                        SecurityFailureCause.INVALID_SCOPE);
            }
        }
    }

    private static Set<String> parseScopeClaim(JWTClaimsSet claims) {
        Object raw = claims.getClaim(SCOPE_CLAIM);
        if (!(raw instanceof String value) || value.isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String token : value.split(SCOPE_SEPARATOR)) {
            if (!token.isBlank()) {
                scopes.add(token);
            }
        }
        return scopes;
    }

    private Principal toPrincipal(JWTClaimsSet claims) {
        Set<String> scopes = parseScopeClaim(claims);
        return Principal.builder()
                .id(claims.getSubject())
                .issuer(claims.getIssuer())
                .scopes(scopes)
                .build();
    }

    /** Reads the {@code iss} claim without verifying the signature. */
    public static String peekIssuer(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            JWT parsed = JWTParser.parse(token);
            JWTClaimsSet claims = parsed.getJWTClaimsSet();
            return claims == null ? null : claims.getIssuer();
        } catch (ParseException ex) {
            return null;
        }
    }

    public static final class Builder {

        private JwksKeyStore jwksKeyStore;
        private URI jwksUri;
        private String expectedIssuer;
        private String expectedAudience;
        private Set<String> requiredScopes = Set.of();
        private Set<JWSAlgorithm> allowedAlgorithms = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);
        private long clockSkewSeconds = 60;
        private long verificationCacheTtlSeconds = 30;
        private int verificationCacheMaxSize = 1000;
        private Http2ClientSettings buildSettings;

        public Builder jwksKeyStore(JwksKeyStore jwksKeyStore) {
            this.jwksKeyStore = jwksKeyStore;
            return this;
        }

        public Builder jwksUri(URI jwksUri) {
            this.jwksUri = jwksUri;
            return this;
        }

        public Builder expectedIssuer(String expectedIssuer) {
            this.expectedIssuer = expectedIssuer;
            return this;
        }

        public Builder expectedAudience(String expectedAudience) {
            this.expectedAudience = expectedAudience;
            return this;
        }

        public Builder requiredScopes(Set<String> requiredScopes) {
            this.requiredScopes = requiredScopes == null ? Set.of() : requiredScopes;
            return this;
        }

        public Builder allowedAlgorithms(Set<JWSAlgorithm> allowedAlgorithms) {
            if (allowedAlgorithms != null && !allowedAlgorithms.isEmpty()) {
                this.allowedAlgorithms = allowedAlgorithms;
            }
            return this;
        }

        public Builder clockSkew(Duration clockSkew) {
            this.clockSkewSeconds = clockSkew == null ? 0 : clockSkew.getSeconds();
            return this;
        }

        public Builder clockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
            return this;
        }

        public Builder verificationCacheTtlSeconds(long verificationCacheTtlSeconds) {
            this.verificationCacheTtlSeconds = verificationCacheTtlSeconds;
            return this;
        }

        public Builder verificationCacheMaxSize(int verificationCacheMaxSize) {
            this.verificationCacheMaxSize = verificationCacheMaxSize;
            return this;
        }

        public Builder http2BuildSettings(Http2ClientSettings buildSettings) {
            this.buildSettings = buildSettings;
            return this;
        }

        public AsymmetricJwtAuthenticator build() {
            return new AsymmetricJwtAuthenticator(this);
        }
    }
}
