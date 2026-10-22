package com.paicore.sba.stack.discovery;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.net.URI;
import java.time.Duration;

@Getter
@Builder
@AllArgsConstructor
public class NrfDiscoverySettings implements DiscoveryConfig {

    private final URI nrfBaseUri;

    @Builder.Default
    private final Duration requestTimeout = Duration.ofSeconds(5);

    @Builder.Default
    private final String requesterNfType = "SMSF";

    @Builder.Default
    private final String discoveryPath = "/nnrf-disc/v1/nf-instances";
}
