package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyAuthenticatorTest {

    @Test
    void acceptsMatchingKey() {
        ApiKeyAuthenticator auth = new ApiKeyAuthenticator("secret-123");
        Principal principal = auth.authenticate("secret-123");
        assertEquals("api-key", principal.getId());
        assertTrue(principal.getScopes().contains("smsf.api"));
    }

    @Test
    void rejectsWrongKey() {
        ApiKeyAuthenticator auth = new ApiKeyAuthenticator("secret-123");
        assertThrows(AuthenticationException.class, () -> auth.authenticate("wrong"));
    }

    @Test
    void rejectsNullCredential() {
        ApiKeyAuthenticator auth = new ApiKeyAuthenticator("secret-123");
        assertThrows(AuthenticationException.class, () -> auth.authenticate(null));
    }

    @Test
    void constantTimeEqualsBehaves() {
        assertTrue(ApiKeyAuthenticator.constantTimeEquals("abc", "abc"));
        assertFalse(ApiKeyAuthenticator.constantTimeEquals("abc", "abd"));
        assertFalse(ApiKeyAuthenticator.constantTimeEquals("abc", "abcd"));
    }
}
