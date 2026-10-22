package com.paicore.sba.stack.security;

import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.auth.Principal;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityKitProviderTest {

    @Mock
    private Authenticator authenticator;

    private SecurityKitProvider provider;

    @BeforeEach
    void setup() {
        provider = new SecurityKitProvider(authenticator);
    }

    @Test
    @DisplayName("authenticate returns true when the inner Authenticator succeeds")
    void authenticateShouldReturnTrueWhenInnerAuthenticatorSucceeds() {
        when(authenticator.authenticate("bearer-xyz"))
                .thenReturn(Principal.builder().id("user-1").build());

        boolean result = provider.authenticate("bearer-xyz");

        assertTrue(result);
        verify(authenticator).authenticate("bearer-xyz");
    }

    @Test
    @DisplayName("authenticate returns false when the inner Authenticator throws")
    void authenticateShouldReturnFalseWhenInnerAuthenticatorThrows() {
        when(authenticator.authenticate("bad-creds"))
                .thenThrow(new AuthenticationException("invalid"));

        boolean result = provider.authenticate("bad-creds");

        assertFalse(result);
    }

    @Test
    @DisplayName("apiKey factory pins authentication to the expected header value")
    void apiKeyFactoryShouldAlwaysCheckExpectedHeaderValue() {
        SecurityKitProvider apiKeyProvider = SecurityKitProvider.apiKey("abc-123", "abc-123");
        // Any credential argument should be ignored — the factory uses the fixed headerValue.
        assertTrue(apiKeyProvider.authenticate("anything-the-caller-sends"));
    }

    @Test
    @DisplayName("apiKey factory rejects when the bound header value does not match the expected one")
    void apiKeyFactoryShouldFailWhenBoundHeaderIsWrong() {
        SecurityKitProvider apiKeyProvider = SecurityKitProvider.apiKey("wrong-header", "expected-header");
        assertFalse(apiKeyProvider.authenticate("ignored"));
    }
}
