package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.api.config.SecurityConfig;

/**
 * SPI implemented by security kits to build a configured {@link SecurityProvider}.
 * Loaded via {@link java.util.ServiceLoader} by the SbaStack builder.
 * Implementations must have a public no-arg constructor.
 */
public interface SecurityProviderFactory {

    /**
     * Returns a security provider configured from {@code config}, or {@code null}
     * when the supplied config is not understood by this factory (the stack will
     * then run without authentication — useful for unit tests).
     */
    SecurityProvider create(SecurityConfig config);
}
