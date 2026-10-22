package com.paicore.sba.stack.discovery;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.spi.NrfClientFactory;

public class Http2NrfClientFactory implements NrfClientFactory {

    @Override
    public NrfClient create(DiscoveryConfig config) {
        if (!(config instanceof NrfDiscoverySettings settings) || settings.getNrfBaseUri() == null) {
            return null;
        }
        return new Http2NrfClient(settings);
    }
}
