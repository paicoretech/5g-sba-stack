package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuth2ClientCredentialsProviderTest {

    @Test
    void fetchesTokenLazilyAndCachesUntilExpiry() {
        AtomicInteger calls = new AtomicInteger();
        OAuth2ClientCredentialsProvider provider = new OAuth2ClientCredentialsProvider(
                URI.create("https://idp/token"), "id", "secret", req -> {
            calls.incrementAndGet();
            return new OAuth2ClientCredentialsProvider.TokenResponse("tok-" + calls.get(), Duration.ofMinutes(5));
        });
        String first = provider.acquireToken();
        String second = provider.acquireToken();
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void emptyTokenSurfacesAuthenticationException() {
        OAuth2ClientCredentialsProvider provider = new OAuth2ClientCredentialsProvider(
                URI.create("https://idp/token"), "id", "secret",
                req -> new OAuth2ClientCredentialsProvider.TokenResponse(null, Duration.ZERO));
        assertThrows(AuthenticationException.class, provider::acquireToken);
    }

    @Test
    void nullResponseAlsoSurfacesAuthenticationException() {
        OAuth2ClientCredentialsProvider provider = new OAuth2ClientCredentialsProvider(
                URI.create("https://idp/token"), "id", "secret", req -> null);
        assertThrows(AuthenticationException.class, provider::acquireToken);
    }

    @Test
    void expiredTokenIsRefreshedOnNextAcquire() {
        AtomicInteger calls = new AtomicInteger();
        OAuth2ClientCredentialsProvider provider = new OAuth2ClientCredentialsProvider(
                URI.create("https://idp/token"), "id", "secret", req -> {
            calls.incrementAndGet();
            // Token validity smaller than the 30s safety margin → always considered expired.
            return new OAuth2ClientCredentialsProvider.TokenResponse(
                    "tok-" + calls.get(), Duration.ofSeconds(5));
        });
        provider.acquireToken();
        provider.acquireToken();
        assertEquals(2, calls.get());
    }
}
