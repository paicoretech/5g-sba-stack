package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Optional kit — requires jjwt on the classpath. Verifies HS256 JWTs signed
 * with a shared secret. Use case: operator-issued service tokens.
 */
@RequiredArgsConstructor
public class JwtAuthenticator implements Authenticator {

    private final SecretKey signingKey;
    private final String expectedIssuer;

    public static JwtAuthenticator withSharedSecret(String secret, String issuer) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        return new JwtAuthenticator(new SecretKeySpec(bytes, "HmacSHA256"), issuer);
    }

    @Override
    public Principal authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("Missing JWT");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (expectedIssuer != null && !expectedIssuer.equals(claims.getIssuer())) {
                throw new AuthenticationException("Untrusted issuer: " + claims.getIssuer());
            }
            Object scopeClaim = claims.get("scope");
            Set<String> scopes = scopeClaim == null
                    ? Set.of()
                    : Set.of(scopeClaim.toString().split("\\s+"));
            return Principal.builder()
                    .id(claims.getSubject())
                    .issuer(claims.getIssuer())
                    .scopes(scopes)
                    .build();
        } catch (JwtException e) {
            throw new AuthenticationException("Invalid JWT", e);
        }
    }
}
