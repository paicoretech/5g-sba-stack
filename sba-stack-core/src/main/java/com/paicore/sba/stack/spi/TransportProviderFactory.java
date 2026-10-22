package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.api.config.Http2Config;

/**
 * SPI implemented by transport kits to build a configured {@link TransportProvider}.
 * Loaded via {@link java.util.ServiceLoader} by the SbaStack builder.
 * Implementations must have a public no-arg constructor.
 */
public interface TransportProviderFactory {

    /**
     * Returns a transport provider configured from {@code config}, or {@code null}
     * when the supplied config is not understood by this factory (the stack will
     * then run without a transport — useful for unit tests).
     */
    TransportProvider create(Http2Config config);
}
