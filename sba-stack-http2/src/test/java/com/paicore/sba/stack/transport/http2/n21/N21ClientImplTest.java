package com.paicore.sba.stack.transport.http2.n21;

import com.paicore.sba.stack.api.n21.N21Configuration;
import com.paicore.sba.stack.api.n21.SmsfContext;
import com.paicore.sba.stack.api.n21.SmsfRegistrationResponse;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.transport.http2.Http2Client;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class N21ClientImplTest {

    private static final String INSTANCE_ID = "8f0d7f6d-62c5-4fd7-ba4c-f8cb9df83a75";

    private static SmsfContext smsfContext() {
        return SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();
    }

    private static UeSmsContextData ue(String supi) {
        UeSmsContextData ue = new UeSmsContextData();
        ue.setSupi(supi);
        com.paicore.sba.stack.openapi.model.Guami guami = new com.paicore.sba.stack.openapi.model.Guami();
        guami.setPlmnId(new com.paicore.sba.stack.openapi.model.PlmnId().mcc("001").mnc("01"));
        ue.setGuamis(java.util.List.of(guami));
        return ue;
    }

    private static Http2Response response(int status, String body) {
        Http2Response r = new Http2Response();
        r.setStatus(status);
        r.setHeaders(new java.util.HashMap<>());
        if (body != null) {
            r.setBody(body.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private static Function<Http2ClientSettings, Http2Client> factoryReturning(Http2Client client) {
        return settings -> client;
    }

    @Test
    void registerBuildsPayloadAndSendsPut() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        Http2Response created = response(201, null);
        created.getHeaders().put("location", "https://udm/nudm-uecm/v1/imsi-1/registrations/smsf-3gpp-access");
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(created));

        N21Configuration config = N21Configuration.builder()
                .apiRoot("https://udm.example.com:8443")
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(udm));

        client.connect();
        SmsfRegistrationResponse res = client.registerSubscriber(
                "/nudm-uecm/v1/{supi}/registrations/smsf-3gpp-access", ue("imsi-001010000000001"));

        assertThat(res.getStatusCode()).isEqualTo(201);
        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getLocation()).contains("smsf-3gpp-access");

        org.mockito.Mockito.verify(udm).send(captor.capture());
        Http2Request sent = captor.getValue();
        assertThat(sent.getMethod()).isEqualTo("PUT");
        assertThat(sent.getPath()).isEqualTo("/nudm-uecm/v1/imsi-001010000000001/registrations/smsf-3gpp-access");
        assertThat(sent.getHeaders()).containsEntry("content-type", "application/json");
        String bodyJson = new String(sent.getBody(), StandardCharsets.UTF_8);
        assertThat(bodyJson).contains("smsfInstanceId").contains("001");
    }

    @Test
    void resolvesUdmApiRootViaNrfDiscoveryWhenEnabled() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"nfType\":\"UDM\","
                + "\"nfServices\":[{\"apiPrefix\":\"https://udm-discovered.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.connect();
        SmsfRegistrationResponse res = client.deregisterSubscriber("/nudm-uecm/v1/{ueId}/registrations/smsf-3gpp-access",
                ue("imsi-1"));

        assertThat(res.getStatusCode()).isEqualTo(204);
        // Discovery request went to the NRF client.
        ArgumentCaptor<Http2Request> nrfReq = ArgumentCaptor.forClass(Http2Request.class);
        org.mockito.Mockito.verify(nrf).send(nrfReq.capture());
        assertThat(nrfReq.getValue().getPath()).contains("/nnrf-disc/v1/nf-instances");
        assertThat(nrfReq.getValue().getPath()).contains("target-nf-type=UDM");
    }

    @Test
    void discoveryEnabledButNrfNotConnectedFails() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(false);
        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(mock(Http2Client.class)));

        assertThatThrownBy(client::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NRF client is not connected");
    }

    @Test
    void discoveryDisabledWithoutApiRootFails() {
        N21Configuration config = N21Configuration.builder().build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(mock(Http2Client.class)));
        assertThatThrownBy(client::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiRoot is required");
    }

    @Test
    void sendWithoutExplicitConnectOpensTheTransport() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, "{}")));

        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(udm));

        SmsfRegistrationResponse res = client.getRegistration("/x/{supi}", "imsi-1");

        assertThat(res.getStatusCode()).isEqualTo(200);
        org.mockito.Mockito.verify(udm).connect();
    }

    @Test
    void aTransportFailureIsNotRetriedAndPropagates() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("boom")));

        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(udm));
        UeSmsContextData ueContext = ue("imsi-1");

        assertThatThrownBy(() -> client.deregisterSubscriber(null, ueContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        org.mockito.Mockito.verify(udm, org.mockito.Mockito.times(1)).send(any());
    }

    @Test
    void theBrokenConnectionIsDiscardedSoTheNextRequestReconnects() {
        Http2Client failing = mock(Http2Client.class);
        when(failing.isConnected()).thenReturn(true);
        when(failing.send(any())).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("boom")));

        Http2Client healthy = mock(Http2Client.class);
        when(healthy.isConnected()).thenReturn(true);
        when(healthy.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        java.util.Iterator<Http2Client> clients = java.util.List.of(failing, healthy).iterator();
        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, settings -> clients.next());
        UeSmsContextData firstUeContext = ue("imsi-1");

        assertThatThrownBy(() -> client.deregisterSubscriber(null, firstUeContext))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(failing).close();

        // The next request opens a new transport rather than reusing the broken one.
        assertThat(client.deregisterSubscriber(null, ue("imsi-2")).getStatusCode()).isEqualTo(204);
    }

    @Test
    void anAuthorizationRejectionIsReturnedAsIs() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(403, null)));

        N21Configuration config = N21Configuration.builder()
                .apiRoot("https://udm:8443")
                .oauth2Enabled(true)
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, () -> "Bearer stale",
                factoryReturning(udm));

        SmsfRegistrationResponse res = client.deregisterSubscriber(null, ue("imsi-1"));

        assertThat(res.getStatusCode()).isEqualTo(403);
        assertThat(res.isSuccess()).isFalse();
        org.mockito.Mockito.verify(udm, org.mockito.Mockito.times(1)).send(any());
    }

    @Test
    void aFailedConnectDropsTheCachedDiscoveryEntry() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"validityPeriod\":600,\"nfInstances\":[{\"nfInstanceId\":\"udm-1\","
                + "\"nfServices\":[{\"serviceName\":\"nudm-uecm\","
                + "\"apiPrefix\":\"https://udm-1.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("connect refused")).when(udm).connect();

        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));
        UeSmsContextData firstUeContext = ue("imsi-1");
        UeSmsContextData secondUeContext = ue("imsi-2");

        assertThatThrownBy(() -> client.deregisterSubscriber(null, firstUeContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect refused");
        assertThatThrownBy(() -> client.deregisterSubscriber(null, secondUeContext))
                .isInstanceOf(IllegalStateException.class);

        // Discovery ran again instead of reusing the entry for an unreachable UDM.
        org.mockito.Mockito.verify(nrf, org.mockito.Mockito.times(2)).send(any());
    }

    @Test
    void usesThe3gppDefaultPathWhenNoneIsConfigured() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(udm));

        client.registerSubscriber(null, ue("imsi-001010000000001"));

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getPath())
                .isEqualTo("/nudm-uecm/v1/imsi-001010000000001/registrations/smsf-3gpp-access");
    }

    @Test
    void takesTheApiVersionAdvertisedByTheNrf() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"nfServices\":[{"
                + "\"serviceName\":\"nudm-uecm\","
                + "\"versions\":[{\"apiVersionInUri\":\"v2\",\"apiFullVersion\":\"2.0.0\"}],"
                + "\"apiPrefix\":\"https://udm-1.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.registerSubscriber(null, ue("imsi-001010000000001"));

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getPath())
                .isEqualTo("/nudm-uecm/v2/imsi-001010000000001/registrations/smsf-3gpp-access");
    }

    @Test
    void theConfiguredPathOverridesTheVersionAdvertisedByTheNrf() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"nfServices\":[{"
                + "\"serviceName\":\"nudm-uecm\","
                + "\"versions\":[{\"apiVersionInUri\":\"v2\"}],"
                + "\"apiPrefix\":\"https://udm-1.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, "{}")));

        N21Configuration config = N21Configuration.builder()
                .nrfDiscoveryEnabled(true)
                .subscriberQueryPath("/nudm-uecm/v3/{ueId}/registrations/smsf-3gpp-access")
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.getRegistration(null, "imsi-1");

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getPath())
                .isEqualTo("/nudm-uecm/v3/imsi-1/registrations/smsf-3gpp-access");
    }

    @Test
    void theApiPrefixPathSegmentIsKeptAheadOfTheResource() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"nfServices\":[{"
                + "\"serviceName\":\"nudm-uecm\","
                + "\"versions\":[{\"apiVersionInUri\":\"v1\"}],"
                + "\"apiPrefix\":\"https://udm-1.example.com:8443/core5g\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.deregisterSubscriber(null, ue("imsi-1"));

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getPath())
                .isEqualTo("/core5g/nudm-uecm/v1/imsi-1/registrations/smsf-3gpp-access");
    }

    @Test
    void appendsTheUeIdWhenTheConfiguredPathHasNoPlaceholder() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null, factoryReturning(udm));

        client.deregisterSubscriber("/nudm-uecm/v1", ue("imsi-1"));

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getPath()).isEqualTo("/nudm-uecm/v1/imsi-1");
    }

    @Test
    void discoveryQueryCarriesTheServiceNameAndSubscriberSelectors() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"nfInstances\":[{\"nfInstanceId\":\"udm-1\",\"nfStatus\":\"REGISTERED\","
                + "\"nfServices\":[{\"serviceName\":\"nudm-uecm\","
                + "\"apiPrefix\":\"https://udm-1.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        UeSmsContextData ueContext = ue("imsi-001010000000001");
        ueContext.setRoutingIndicator("1234");
        ueContext.setUdmGroupId("udm-group-7");

        N21Configuration config = N21Configuration.builder()
                .nrfDiscoveryEnabled(true)
                .requesterNfInstanceId("11111111-2222-3333-4444-555555555555")
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.deregisterSubscriber(null, ueContext);

        ArgumentCaptor<Http2Request> nrfReq = ArgumentCaptor.forClass(Http2Request.class);
        org.mockito.Mockito.verify(nrf).send(nrfReq.capture());
        assertThat(nrfReq.getValue().getPath())
                .contains("service-names=nudm-uecm")
                .contains("routing-indicator=1234")
                .contains("group-id-list=udm-group-7")
                .contains("requester-nf-instance-id=11111111-2222-3333-4444-555555555555");
    }

    @Test
    void reusesTheCachedDiscoveryResultAcrossRequests() {
        Http2Client nrf = mock(Http2Client.class);
        when(nrf.isConnected()).thenReturn(true);
        String discoveryBody = "{\"validityPeriod\":600,\"nfInstances\":[{\"nfInstanceId\":\"udm-1\","
                + "\"nfServices\":[{\"serviceName\":\"nudm-uecm\","
                + "\"apiPrefix\":\"https://udm-1.example.com:8443\"}]}]}";
        when(nrf.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, discoveryBody)));

        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(204, null)));

        N21Configuration config = N21Configuration.builder().nrfDiscoveryEnabled(true).build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), nrf, null, factoryReturning(udm));

        client.deregisterSubscriber(null, ue("imsi-1"));
        client.deregisterSubscriber(null, ue("imsi-2"));

        org.mockito.Mockito.verify(nrf, org.mockito.Mockito.times(1)).send(any());
        org.mockito.Mockito.verify(udm, org.mockito.Mockito.times(2)).send(any());
    }

    @Test
    void noAuthorizationHeaderWhenOauthDisabled() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, "{}")));

        // oauth2Enabled defaults to false; a token provider is present but must be ignored.
        N21Configuration config = N21Configuration.builder().apiRoot("https://udm:8443").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null,
                () -> "Bearer must-not-be-sent", factoryReturning(udm));

        client.connect();
        client.getRegistration("/nudm-uecm/v1/{supi}/registrations/smsf-3gpp-access", "imsi-1");

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getHeaders()).doesNotContainKey("authorization");
    }

    @Test
    void noClientCertificateWhenMtlsDisabled() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<Http2ClientSettings> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        // mtlsEnabled defaults to false; the client cert/key are supplied but must be ignored.
        N21Configuration config = N21Configuration.builder()
                .apiRoot("https://udm:8443")
                .clientCertificatePath("/should/not/be/used/cert.pem")
                .clientPrivateKeyPath("/should/not/be/used/key.pem")
                .truststoreCaPath("/etc/certs/ca.pem")
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null,
                settings -> {
                    captured.set(settings);
                    return udm;
                });

        client.connect();

        TlsSettings tls = captured.get().getTls();
        // An https apiRoot still needs TLS — server-authenticated, with the configured CA.
        assertThat(tls.isEnabled()).isTrue();
        assertThat(tls.getKeyStorePath()).isNull();
        assertThat(tls.getKeyPath()).isNull();
        assertThat(tls.getTrustStorePath()).isEqualTo("/etc/certs/ca.pem");
    }

    @Test
    void tlsIsDisabledForACleartextApiRoot() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<Http2ClientSettings> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        N21Configuration config = N21Configuration.builder().apiRoot("http://udm:8080").build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null,
                settings -> {
                    captured.set(settings);
                    return udm;
                });

        client.connect();

        assertThat(captured.get().getPort()).isEqualTo(8080);
        assertThat(captured.get().getTls().isEnabled()).isFalse();
    }

    @Test
    void usesClientCertificateWhenMtlsEnabled() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<Http2ClientSettings> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        N21Configuration config = N21Configuration.builder()
                .apiRoot("https://udm:8443")
                .mtlsEnabled(true)
                .clientCertificatePath("/etc/certs/cert.pem")
                .clientPrivateKeyPath("/etc/certs/key.pem")
                .truststoreCaPath("/etc/certs/ca.pem")
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null, null,
                settings -> {
                    captured.set(settings);
                    return udm;
                });

        client.connect();

        TlsSettings tls = captured.get().getTls();
        assertThat(tls.isEnabled()).isTrue();
        assertThat(tls.getKeyStorePath()).isEqualTo("/etc/certs/cert.pem");
        assertThat(tls.getKeyPath()).isEqualTo("/etc/certs/key.pem");
        assertThat(tls.getTrustStorePath()).isEqualTo("/etc/certs/ca.pem");
    }

    @Test
    void attachesAuthorizationHeaderWhenOauthEnabled() {
        Http2Client udm = mock(Http2Client.class);
        when(udm.isConnected()).thenReturn(true);
        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        when(udm.send(any())).thenReturn(CompletableFuture.completedFuture(response(200, "{}")));

        N21Configuration config = N21Configuration.builder()
                .apiRoot("https://udm:8443")
                .oauth2Enabled(true)
                .build();
        N21ClientImpl client = new N21ClientImpl(config, smsfContext(), null,
                () -> "Bearer token-123", factoryReturning(udm));

        client.connect();
        client.getRegistration("/nudm-uecm/v1/{supi}/registrations/smsf-3gpp-access", "imsi-1");

        org.mockito.Mockito.verify(udm).send(captor.capture());
        assertThat(captor.getValue().getHeaders()).containsEntry("authorization", "Bearer token-123");
    }
}
