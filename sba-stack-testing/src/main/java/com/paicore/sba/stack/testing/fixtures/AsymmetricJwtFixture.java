package com.paicore.sba.stack.testing.fixtures;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builder for asymmetrically-signed test JWTs paired with an {@link AsymmetricKeyFixture}. */
public final class AsymmetricJwtFixture {

    private final AsymmetricKeyFixture keys;
    private final JWSAlgorithm algorithm;

    private String issuer = "https://issuer.local";
    private String subject = UUID.randomUUID().toString();
    private final List<String> audience = new ArrayList<>();
    private final List<String> scopes = new ArrayList<>();
    private final Map<String, Object> extraClaims = new LinkedHashMap<>();
    private Duration validity = Duration.ofMinutes(5);
    private Duration issuedAtOffset = Duration.ZERO;

    private AsymmetricJwtFixture(AsymmetricKeyFixture keys, JWSAlgorithm algorithm) {
        this.keys = keys;
        this.algorithm = algorithm;
    }

    public static AsymmetricJwtFixture rs256(AsymmetricKeyFixture keys) {
        return new AsymmetricJwtFixture(keys, JWSAlgorithm.RS256);
    }

    public static AsymmetricJwtFixture es256(AsymmetricKeyFixture keys) {
        return new AsymmetricJwtFixture(keys, JWSAlgorithm.ES256);
    }

    public AsymmetricJwtFixture issuer(String value) {
        this.issuer = value;
        return this;
    }

    public AsymmetricJwtFixture subject(String value) {
        this.subject = value;
        return this;
    }

    public AsymmetricJwtFixture audience(String value) {
        this.audience.clear();
        if (value != null) {
            this.audience.add(value);
        }
        return this;
    }

    public AsymmetricJwtFixture audiences(List<String> values) {
        this.audience.clear();
        if (values != null) {
            this.audience.addAll(values);
        }
        return this;
    }

    public AsymmetricJwtFixture scope(String value) {
        if (value != null && !value.isBlank()) {
            this.scopes.add(value);
        }
        return this;
    }

    public AsymmetricJwtFixture scopes(String... values) {
        if (values != null) {
            for (String value : values) {
                scope(value);
            }
        }
        return this;
    }

    public AsymmetricJwtFixture claim(String name, Object value) {
        if (name != null) {
            extraClaims.put(name, value);
        }
        return this;
    }

    public AsymmetricJwtFixture validity(Duration value) {
        this.validity = value == null ? Duration.ZERO : value;
        return this;
    }

    /** Issues a token whose {@code exp} is in the past by {@code by}. */
    public AsymmetricJwtFixture expiredBy(Duration by) {
        Duration nonNull = by == null ? Duration.ofSeconds(60) : by;
        this.issuedAtOffset = nonNull.negated().minusSeconds(60);
        this.validity = Duration.ofSeconds(60).minus(nonNull);
        return this;
    }

    public String sign() {
        Instant base = Instant.now().plus(issuedAtOffset);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(base))
                .expirationTime(Date.from(base.plus(validity)));
        if (!audience.isEmpty()) {
            builder.audience(audience);
        }
        if (!scopes.isEmpty()) {
            builder.claim("scope", String.join(" ", scopes));
        }
        extraClaims.forEach(builder::claim);

        JWSHeader header = new JWSHeader.Builder(algorithm)
                .type(JOSEObjectType.JWT)
                .keyID(keys.kid())
                .build();
        SignedJWT signed = new SignedJWT(header, builder.build());
        try {
            signed.sign(signer());
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign test JWT", ex);
        }
        return signed.serialize();
    }

    private JWSSigner signer() throws JOSEException {
        if (algorithm.equals(JWSAlgorithm.RS256)) {
            return new RSASSASigner(keys.asRsa());
        }
        if (algorithm.equals(JWSAlgorithm.ES256)) {
            return new ECDSASigner(keys.asEc());
        }
        throw new IllegalStateException("Unsupported algorithm: " + algorithm);
    }
}
