package com.paicore.sba.stack.api.security;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies the {@code Authorization} header value for outbound SBI requests.
 *
 * <p>Deliberately a supplier rather than a fixed string: OAuth2 access tokens rotate, so the
 * value must be read at request time rather than captured when the client is built.</p>
 */
@FunctionalInterface
public interface AuthorizationProvider {

    /**
     * @return the full header value (e.g. {@code "Bearer eyJ..."}), or {@code null}/blank
     *         when no credential is currently available
     */
    String authorizationHeader();

    /** Adapts a plain supplier. */
    static AuthorizationProvider of(Supplier<String> supplier) {
        if (Objects.isNull(supplier)) {
            return null;
        }
        return supplier::get;
    }
}
