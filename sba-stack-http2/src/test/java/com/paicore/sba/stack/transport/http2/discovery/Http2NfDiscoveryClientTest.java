package com.paicore.sba.stack.transport.http2.discovery;

import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryQuery;
import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryResult;
import com.paicore.sba.stack.api.security.AuthorizationProvider;
import com.paicore.sba.stack.transport.http2.Http2Client;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Http2NfDiscoveryClientTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    private static final String BODY = """
            {"validityPeriod":600,"nfInstances":[{"nfInstanceId":"udm-1","nfStatus":"REGISTERED",
              "nfServices":[{"serviceName":"nudm-uecm","apiPrefix":"https://udm-1.example.com:8443"}]}]}
            """;

    private static NfDiscoveryQuery query() {
        return NfDiscoveryQuery.builder()
                .targetNfType("UDM")
                .requesterNfType("SMSF")
                .serviceName("nudm-uecm")
                .build();
    }

    private static Http2Response response(int status, String body) {
        Http2Response response = new Http2Response();
        response.setStatus(status);
        response.setHeaders(new HashMap<>());
        if (body != null) {
            response.setBody(body.getBytes(StandardCharsets.UTF_8));
        }
        return response;
    }

    private static Http2Client connectedNrf(Http2Response response) {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response));
        return nrf;
    }

    private static Http2NfDiscoveryClient.Http2NfDiscoveryClientBuilder clientFor(Http2Client nrf) {
        return Http2NfDiscoveryClient.builder()
                .nrfClient(nrf)
                .requestTimeoutMs(1_000)
                .clock(() -> NOW);
    }

    @Test
    void issuesTheDiscoveryRequestAndRanksTheResult() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);

        NfDiscoveryResult result = clientFor(nrf).build().discover(query());

        assertThat(result.preferredApiRoot()).contains("https://udm-1.example.com:8443");
        verify(nrf).send(captor.capture());
        assertThat(captor.getValue().getPath())
                .startsWith("/nnrf-disc/v1/nf-instances?")
                .contains("target-nf-type=UDM")
                .contains("service-names=nudm-uecm");
        assertThat(captor.getValue().getMethod()).isEqualTo("GET");
    }

    @Test
    void servesTheCachedResultWhileTheValidityPeriodHolds() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        Http2NfDiscoveryClient client = clientFor(nrf).build();

        client.discover(query());
        client.discover(query());

        verify(nrf, times(1)).send(any());
    }

    @Test
    void reResolvesOnceTheValidityPeriodHasElapsed() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        Http2NfDiscoveryClient client = Http2NfDiscoveryClient.builder()
                .nrfClient(nrf)
                .clock(clock::get)
                .build();

        client.discover(query());
        clock.set(NOW.plusSeconds(601));
        client.discover(query());

        verify(nrf, times(2)).send(any());
    }

    @Test
    void invalidateForcesAFreshLookup() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        Http2NfDiscoveryClient client = clientFor(nrf).build();

        client.discover(query());
        client.invalidate(query());
        client.discover(query());

        verify(nrf, times(2)).send(any());
    }

    @Test
    void invalidateAllClearsEveryEntry() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        Http2NfDiscoveryClient client = clientFor(nrf).build();

        client.discover(query());
        client.invalidateAll();
        client.discover(query());

        verify(nrf, times(2)).send(any());
    }

    @Test
    void attachesTheAuthorizationHeaderWhenAProviderIsSet() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);

        clientFor(nrf).authorizationProvider(() -> "Bearer nrf-token").build().discover(query());

        verify(nrf).send(captor.capture());
        assertThat(captor.getValue().getHeaders()).containsEntry("authorization", "Bearer nrf-token");
    }

    @Test
    void honoursACustomAuthorizationHeaderName() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);

        clientFor(nrf)
                .authorizationProvider(() -> "Bearer nrf-token")
                .authorizationHeaderName("X-Authorization")
                .build()
                .discover(query());

        verify(nrf).send(captor.capture());
        assertThat(captor.getValue().getHeaders()).containsEntry("x-authorization", "Bearer nrf-token");
    }

    @Test
    void omitsTheAuthorizationHeaderWhenTheProviderHasNoCredential() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        AuthorizationProvider empty = () -> "  ";

        clientFor(nrf).authorizationProvider(empty).build().discover(query());

        verify(nrf).send(captor.capture());
        assertThat(captor.getValue().getHeaders()).doesNotContainKey("authorization");
    }

    @Test
    void failsWhenTheNrfClientIsNotConnected() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(false);
        Http2NfDiscoveryClient client = clientFor(nrf).build();
        NfDiscoveryQuery discoveryQuery = query();

        assertThatThrownBy(() -> client.discover(discoveryQuery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NRF client is not connected");
        verify(nrf, never()).send(any());
    }

    @Test
    void failsWhenTheNrfReturnsAnErrorStatus() {
        Http2Client nrf = connectedNrf(response(500, null));
        Http2NfDiscoveryClient client = clientFor(nrf).build();
        NfDiscoveryQuery discoveryQuery = query();

        assertThatThrownBy(() -> client.discover(discoveryQuery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void anEmptySearchResultIsNotCached() {
        Http2Client nrf = connectedNrf(response(200, "{\"nfInstances\":[]}"));
        Http2NfDiscoveryClient client = clientFor(nrf).build();

        assertThat(client.discover(query()).isEmpty()).isTrue();
        assertThat(client.discover(query()).isEmpty()).isTrue();

        verify(nrf, times(2)).send(any());
    }

    @Test
    void appliesTheDefaultTtlWhenTheNrfOmitsTheValidityPeriod() {
        String noValidity = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"fqdn\":\"udm.example.com\"}]}";
        Http2Client nrf = connectedNrf(response(200, noValidity));

        NfDiscoveryResult result = clientFor(nrf).defaultTtl(Duration.ofSeconds(45)).build()
                .discover(NfDiscoveryQuery.builder().targetNfType("UDM").build());

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(45));
    }

    @Test
    void rejectsANullQuery() {
        Http2Client nrf = connectedNrf(response(200, BODY));
        Http2NfDiscoveryClient client = clientFor(nrf).build();

        assertThatThrownBy(() -> client.discover(null)).isInstanceOf(NullPointerException.class);
    }
}
