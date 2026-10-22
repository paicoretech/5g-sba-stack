package com.paicore.sba.stack.testing.fixtures;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import java.util.List;

/** RSA/EC test keys for asymmetric JWT round-trips. Asymmetric counterpart of {@link JwtFixture}. */
public final class AsymmetricKeyFixture {

    private final JWK key;

    private AsymmetricKeyFixture(JWK key) {
        this.key = key;
    }

    public static AsymmetricKeyFixture rsa(String kid) {
        try {
            RSAKey rsa = new RSAKeyGenerator(2048).keyID(kid).generate();
            return new AsymmetricKeyFixture(rsa);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA test key", ex);
        }
    }

    public static AsymmetricKeyFixture ec(String kid) {
        try {
            ECKey ec = new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
            return new AsymmetricKeyFixture(ec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate EC test key", ex);
        }
    }

    public static AsymmetricKeyFixture wrap(JWK key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        return new AsymmetricKeyFixture(key);
    }

    public JWK jwk() {
        return key;
    }

    public RSAKey asRsa() {
        if (key instanceof RSAKey rsa) {
            return rsa;
        }
        throw new IllegalStateException("Underlying JWK is not an RSAKey");
    }

    public ECKey asEc() {
        if (key instanceof ECKey ec) {
            return ec;
        }
        throw new IllegalStateException("Underlying JWK is not an ECKey");
    }

    public String kid() {
        return key.getKeyID();
    }

    public JWKSet publicJwks() {
        return new JWKSet(List.<JWK>of(key.toPublicJWK()));
    }

    public String publicJwksJson() {
        return publicJwks().toString();
    }
}
