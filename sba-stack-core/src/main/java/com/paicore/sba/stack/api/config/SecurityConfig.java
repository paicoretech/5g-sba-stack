package com.paicore.sba.stack.api.config;

import java.util.Collection;
import java.util.Collections;

/**
 * Inbound-security policy consumed by the controller layer.
 *
 * <p>The interface stays in the {@code api} module so controllers depend only
 * on the SBA Stack contract — the concrete authenticator (JWT, API key, mTLS)
 * lives in {@code sba-stack-security}.
 *
 * <p>All methods carry "no security" defaults so an empty
 * {@code new SecurityConfig() {}} marker behaves as a disabled config
 * (useful for unit tests and the existing SPI fallback path).
 */
public interface SecurityConfig {

    /** Whether controllers must enforce credentials on inbound requests. */
    default boolean isAuthRequired() {
        return false;
    }

    /** Authorization-header scheme this config expects (e.g. "Bearer", "ApiKey"). */
    default String getAuthScheme() {
        return "Bearer";
    }

    default String getAuthHeaderName() {
        return "Authorization";
    }

    /** Whether the transport must enforce mTLS for inbound clients. */
    default boolean isMtlsRequired() {
        return false;
    }

    /** Ant-style ({@code ?}, {@code *}, {@code **}) paths exempt from authentication. */
    default Collection<String> getBypassPaths() {
        return Collections.emptyList();
    }

    /** Callers should invoke this before {@link #authorize(String)} to skip health/info probes. */
    default boolean isPathBypassed(String path) {
        if (path == null) {
            return false;
        }
        Collection<String> patterns = getBypassPaths();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && AntPathMatcher.matches(pattern.trim(), path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate the credential portion of an Authorization header — the value
     * after the {@link #getAuthScheme() scheme} + space. Returns {@code true}
     * when the credential is accepted by the configured backend.
     */
    default boolean authenticate(String credential) {
        return true;
    }

    /**
     * Parse a full Authorization header, verify the scheme matches, and
     * authenticate the credential. Returns {@code true} when auth is not
     * required, or when the header is present and authenticates.
     */
    default boolean authorize(String authorizationHeader) {
        if (!isAuthRequired()) {
            return true;
        }
        if (authorizationHeader == null) {
            return false;
        }
        String prefix = getAuthScheme() + " ";
        if (!authorizationHeader.startsWith(prefix)) {
            return false;
        }
        return authenticate(authorizationHeader.substring(prefix.length()).trim());
    }

    /** No-auth, no-mTLS sentinel for tests and dev wiring. */
    static SecurityConfig disabled() {
        return new SecurityConfig() {};
    }
}
