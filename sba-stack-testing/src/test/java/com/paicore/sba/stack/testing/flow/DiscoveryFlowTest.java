package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryFlowTest {

    private MockSbaCoreServer mock;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
    }

    @AfterEach
    void stop() {
        if (mock != null) mock.close();
    }

    @Test
    @DisplayName("resolveAll proxies the request to the underlying NrfClient and returns its list")
    void resolveAllReturnsNrfResults() {
        DiscoveryFlow flow = new DiscoveryFlow(mock.baseUri());

        List<NFProfile> all = flow.resolveAll(NFType.UDM);

        assertThat(all).isNotNull();
    }

    @Test
    @DisplayName("resolveOne returns empty when discovery yields no candidates")
    void resolveOneReturnsEmptyWhenNoCandidates() {
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of());
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.resolveOne(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("resolveOne returns one of the discovered profiles when at least one exists")
    void resolveOneReturnsOneCandidate() {
        NFProfile udm1 = NFProfile.builder().nfInstanceId("udm-1").nfType(NFType.UDM).apiRoot("http://udm-1").build();
        NFProfile udm2 = NFProfile.builder().nfInstanceId("udm-2").nfType(NFType.UDM).apiRoot("http://udm-2").build();
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of(udm1, udm2));
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        Optional<NFProfile> picked = flow.resolveOne(NFType.UDM);

        assertThat(picked).isPresent();
        assertThat(picked.get()).isIn(udm1, udm2);
    }

    @Test
    @DisplayName("nextAfter returns empty when the discovery result is empty")
    void nextAfterEmptyWhenNoCandidates() {
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of());
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.nextAfter(NFType.UDM, "udm-1")).isEmpty();
    }

    @Test
    @DisplayName("nextAfter returns the first instance when the current id is null")
    void nextAfterReturnsFirstWhenCurrentIdIsNull() {
        NFProfile udm1 = NFProfile.builder().nfInstanceId("udm-1").nfType(NFType.UDM).build();
        NFProfile udm2 = NFProfile.builder().nfInstanceId("udm-2").nfType(NFType.UDM).build();
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of(udm1, udm2));
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.nextAfter(NFType.UDM, null)).contains(udm1);
    }

    @Test
    @DisplayName("nextAfter wraps around to the first instance when current is the last")
    void nextAfterWrapsAround() {
        NFProfile udm1 = NFProfile.builder().nfInstanceId("udm-1").nfType(NFType.UDM).build();
        NFProfile udm2 = NFProfile.builder().nfInstanceId("udm-2").nfType(NFType.UDM).build();
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of(udm1, udm2));
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.nextAfter(NFType.UDM, "udm-2")).contains(udm1);
    }

    @Test
    @DisplayName("nextAfter picks the neighbour when current is an interior id")
    void nextAfterPicksImmediateNeighbour() {
        NFProfile a = NFProfile.builder().nfInstanceId("a").nfType(NFType.UDM).build();
        NFProfile b = NFProfile.builder().nfInstanceId("b").nfType(NFType.UDM).build();
        NFProfile c = NFProfile.builder().nfInstanceId("c").nfType(NFType.UDM).build();
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of(a, b, c));
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.nextAfter(NFType.UDM, "a")).contains(b);
        assertThat(flow.nextAfter(NFType.UDM, "b")).contains(c);
    }

    @Test
    @DisplayName("nextAfter falls back to the first instance when current id is unknown")
    void nextAfterFallsBackWhenIdUnknown() {
        NFProfile a = NFProfile.builder().nfInstanceId("a").nfType(NFType.UDM).build();
        NFProfile b = NFProfile.builder().nfInstanceId("b").nfType(NFType.UDM).build();
        NrfClient nrf = mock(NrfClient.class);
        when(nrf.discoverInstances(NFType.UDM)).thenReturn(List.of(a, b));
        DiscoveryFlow flow = new DiscoveryFlow(nrf);

        assertThat(flow.nextAfter(NFType.UDM, "not-here")).contains(a);
    }

    @Test
    @DisplayName("the (URI) constructor wires a real Http2NrfClient")
    void uriConstructorBuildsRealClient() {
        DiscoveryFlow flow = new DiscoveryFlow(mock.baseUri());
        assertThat(flow.resolveAll(NFType.UDM)).isNotNull();
    }

    @Test
    @DisplayName("the (URI, Duration) constructor wires a Http2NrfClient with the supplied timeout")
    void uriAndTimeoutConstructorWorks() {
        DiscoveryFlow flow = new DiscoveryFlow(mock.baseUri(), Duration.ofMillis(500));
        assertThat(flow.resolveAll(NFType.UDM)).isNotNull();
    }
}
