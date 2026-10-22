package com.paicore.sba.stack.security;

import com.paicore.sba.stack.security.auth.ApiKeyAuthenticator;
import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.spi.SecurityProvider;
import lombok.RequiredArgsConstructor;

/**
 * Glue between the security kit primitives and the {@link SecurityProvider}
 * SPI from core. Wraps an {@link Authenticator} consumed by transport kits.
 */
@RequiredArgsConstructor
public class SecurityKitProvider implements SecurityProvider {

    private final Authenticator authenticator;

    public static SecurityKitProvider apiKey(String headerValue, String expected) {
        return new SecurityKitProvider(new ApiKeyAuthenticator(expected)) {
            @Override
            public boolean authenticate(String credential) {
                return super.authenticate(headerValue);
            }
        };
    }

    @Override
    public boolean authenticate(String credential) {
        try {
            authenticator.authenticate(credential);
            return true;
        } catch (AuthenticationException e) {
            return false;
        }
    }
}
