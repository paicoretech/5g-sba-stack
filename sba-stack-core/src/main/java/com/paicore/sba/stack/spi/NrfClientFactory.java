package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.nnrf.NrfClient;

/**
 * SPI implemented by discovery kits to build a configured {@link NrfClient}.
 * Loaded via {@link java.util.ServiceLoader} by the SbaStack builder.
 * Implementations must have a public no-arg constructor.
 */
public interface NrfClientFactory {

    /**
     * Returns an NRF client configured from {@code config}, or {@code null}
     * when the supplied config is not understood by this factory (the stack
     * will then fall back to an empty NrfClient — useful for unit tests).
     */
    NrfClient create(DiscoveryConfig config);
}
