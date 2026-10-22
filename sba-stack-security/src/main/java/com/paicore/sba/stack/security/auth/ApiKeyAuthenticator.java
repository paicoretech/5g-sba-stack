package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@RequiredArgsConstructor
public class ApiKeyAuthenticator implements Authenticator {

    private final String expectedKey;

    @Override
    public Principal authenticate(String credential) {
        if (credential == null || expectedKey == null) {
            throw new AuthenticationException("Missing API key");
        }
        if (!constantTimeEquals(credential, expectedKey)) {
            throw new AuthenticationException("Invalid API key");
        }
        return Principal.builder()
                .id("api-key")
                .issuer("local")
                .scopes(Set.of("smsf.api"))
                .build();
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < x.length; i++) {
            result |= x[i] ^ y[i];
        }
        return result == 0;
    }
}
