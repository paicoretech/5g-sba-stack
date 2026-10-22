package com.paicore.sba.stack.security;

import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.security.auth.ApiKeyAuthenticator;
import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import com.paicore.sba.stack.security.config.SecuritySettings;
import com.paicore.sba.stack.spi.SecurityProvider;
import com.paicore.sba.stack.spi.SecurityProviderFactory;

/**
 * Picks the strongest credential the operator configured in
 * {@link SecuritySettings} and wires the matching {@link Authenticator} into a
 * {@link SecurityKitProvider}. Precedence: JWT (shared secret) over API key,
 * since JWT carries claims; falls back to {@code null} when neither is set so
 * the stack runs without authentication.
 */
public class SecurityKitProviderFactory implements SecurityProviderFactory {

    @Override
    public SecurityProvider create(SecurityConfig config) {
        if (!(config instanceof SecuritySettings settings)) {
            return null;
        }
        Authenticator authenticator = pickAuthenticator(settings);
        if (authenticator == null) {
            return null;
        }
        return new SecurityKitProvider(authenticator);
    }

    private static Authenticator pickAuthenticator(SecuritySettings settings) {
        if (isSet(settings.getJwtSecret())) {
            return JwtAuthenticator.withSharedSecret(settings.getJwtSecret(), settings.getJwtIssuer());
        }
        if (isSet(settings.getApiKey())) {
            return new ApiKeyAuthenticator(settings.getApiKey());
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
