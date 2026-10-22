package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.discovery.Http2NrfClient;
import com.paicore.sba.stack.discovery.NrfDiscoverySettings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wires the {@link Http2NrfClient} from {@code sba-stack-discovery} against
 * the mock NRF endpoint and exposes simple lookup + failover helpers.
 *
 * <p>This is what an application looks like when it delegates NRF discovery
 * to the stack — applications never hardcode endpoints, they call
 * {@link #resolveOne(NFType)} and let the discovery layer return a live
 * {@link NFProfile}.
 */
public class DiscoveryFlow {

    private final NrfClient nrfClient;

    public DiscoveryFlow(URI nrfBaseUri) {
        this(nrfBaseUri, Duration.ofSeconds(3));
    }

    public DiscoveryFlow(URI nrfBaseUri, Duration requestTimeout) {
        this.nrfClient = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(nrfBaseUri)
                .requestTimeout(requestTimeout)
                .build());
    }

    DiscoveryFlow(NrfClient nrfClient) {
        this.nrfClient = nrfClient;
    }

    public List<NFProfile> resolveAll(NFType type) {
        return nrfClient.discoverInstances(type);
    }

    public Optional<NFProfile> resolveOne(NFType type) {
        List<NFProfile> all = nrfClient.discoverInstances(type);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(all.get(ThreadLocalRandom.current().nextInt(all.size())));
    }

    /**
     * Returns the next NF instance after {@code currentInstanceId}, mimicking
     * a sticky round-robin so transports can fail over when one instance
     * starts returning errors.
     */
    public Optional<NFProfile> nextAfter(NFType type, String currentInstanceId) {
        List<NFProfile> all = nrfClient.discoverInstances(type);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (currentInstanceId == null) {
            return Optional.of(all.get(0));
        }
        for (int i = 0; i < all.size(); i++) {
            if (currentInstanceId.equals(all.get(i).getNfInstanceId())) {
                return Optional.of(all.get((i + 1) % all.size()));
            }
        }
        return Optional.of(all.get(0));
    }
}
