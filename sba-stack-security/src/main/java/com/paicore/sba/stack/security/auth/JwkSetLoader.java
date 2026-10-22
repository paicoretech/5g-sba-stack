package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;

/**
 * Strategy for materialising a JWK Set on demand. The library owns the cache
 * lifecycle in {@link JwksKeyStore}; consumers provide the transport (HTTP/2
 * to NRF, filesystem, Vault, etc.) by implementing this interface.
 *
 * <p>The loader is bound to a single source — host/port/path are baked into
 * the implementation. {@link JwksKeyStore} caches per loader instance, so
 * pluggable transports stay isolated per gateway.
 *
 * <p>Implementations must throw {@link AuthenticationException} with cause
 * {@code JWKS_UNAVAILABLE} when the set cannot be loaded.
 */
public interface JwkSetLoader {

    JWKSet load();
}
