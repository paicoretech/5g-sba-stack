package com.paicore.sba.stack.discovery;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class Http2NrfClientFactoryTest {

    private final Http2NrfClientFactory factory = new Http2NrfClientFactory();

    @Test
    @DisplayName("create returns null when config is null")
    void returnsNullForNullConfig() {
        assertThat(factory.create(null)).isNull();
    }

    @Test
    @DisplayName("create returns null when config is an unknown DiscoveryConfig marker")
    void returnsNullForUnknownMarker() {
        DiscoveryConfig opaque = new DiscoveryConfig() {};
        assertThat(factory.create(opaque)).isNull();
    }

    @Test
    @DisplayName("create returns null when NrfDiscoverySettings has no base URI")
    void returnsNullWhenBaseUriMissing() {
        NrfDiscoverySettings settings = NrfDiscoverySettings.builder().build();
        assertThat(factory.create(settings)).isNull();
    }

    @Test
    @DisplayName("create returns an Http2NrfClient when settings include a base URI")
    void returnsClientForValidSettings() {
        NrfDiscoverySettings settings = NrfDiscoverySettings.builder()
                .nrfBaseUri(URI.create("http://nrf.example.com:8080"))
                .build();
        NrfClient client = factory.create(settings);
        assertThat(client).isNotNull().isInstanceOf(Http2NrfClient.class);
    }
}
