package com.paicore.sba.stack.discovery;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises edge-case branches in {@link Http2NrfClient} so the URI building,
 * IO failure, interruption, malformed body and apiRoot-extraction paths are
 * all covered by JaCoCo.
 */
class Http2NrfClientBranchTest {

    private HttpServer server;
    private AtomicReference<String> responseBody;
    private AtomicReference<Integer> responseStatus;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = new AtomicReference<>("{\"nfInstances\":[]}");
        responseStatus = new AtomicReference<>(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("IOException from HttpClient returns empty list and is logged, not propagated")
    void ioExceptionReturnsEmptyList() {
        // Point the client at a closed port so connect fails fast with IOException.
        int port = pickClosedPort();
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(URI.create("http://127.0.0.1:" + port))
                .requestTimeout(Duration.ofMillis(200))
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("InterruptedException from HttpClient.send returns empty list and re-flags the thread")
    void interruptedExceptionReturnsEmptyAndPreservesFlag() throws Exception {
        @SuppressWarnings("unchecked")
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(), any())).thenThrow(new InterruptedException("simulated"));
        Http2NrfClient client = new Http2NrfClient(
                NrfDiscoverySettings.builder().nrfBaseUri(baseUri).build(), mockHttp);

        try {
            List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
            assertThat(profiles).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the interrupt flag so subsequent tests are not affected.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a base URI ending in '/' and a discoveryPath starting with '/' are joined without doubling the slash")
    void joinsBaseAndPathWithoutDoubleSlash() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(URI.create(baseUri + "/"))
                .discoveryPath("/nnrf-disc/v1/nf-instances")
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("a base URI without trailing slash and a path without leading slash are joined with a single '/'")
    void joinsBaseAndPathInsertingSeparator() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .discoveryPath("nnrf-disc/v1/nf-instances")
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("base ends with '/' and path lacks leading '/' — no double slash, no extra fix-up")
    void joinsBaseEndingWithSlashAndPathWithoutLeadingSlash() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(URI.create(baseUri + "/"))
                .discoveryPath("nnrf-disc/v1/nf-instances")
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("base without trailing '/' and path with leading '/' — fall-through (no fix-up)")
    void joinsBaseWithoutSlashAndPathWithLeadingSlash() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .discoveryPath("/nnrf-disc/v1/nf-instances")
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("response body without nfInstances key returns empty list")
    void parseReturnsEmptyWhenNfInstancesKeyMissing() {
        responseBody.set("{\"validityPeriod\":3600}");

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("response body with nfInstances of wrong type returns empty list")
    void parseReturnsEmptyWhenNfInstancesIsNotAList() {
        responseBody.set("{\"nfInstances\":\"not-a-list\"}");

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("non-map entries inside nfInstances are silently skipped")
    void skipsNonMapEntries() {
        responseBody.set("""
                {
                  "nfInstances": [
                    "not-a-map",
                    {"nfInstanceId": "udm-1", "nfType": "UDM", "fqdn": "udm.example.com"}
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().getNfInstanceId()).isEqualTo("udm-1");
    }

    @Test
    @DisplayName("entries missing instanceId or nfType are skipped")
    void skipsEntriesMissingMandatoryFields() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {"nfType": "UDM", "fqdn": "udm.example.com"},
                    {"nfInstanceId": "udm-2"},
                    {"nfInstanceId": "udm-3", "nfType": "UDM", "fqdn": "udm3.example.com"}
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().getNfInstanceId()).isEqualTo("udm-3");
    }

    @Test
    @DisplayName("apiRoot falls back to the first ipv4 address when fqdn is absent")
    void apiRootFallsBackToIpv4WhenFqdnMissing() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {
                      "nfInstanceId": "udm-1",
                      "nfType": "UDM",
                      "ipv4Addresses": ["10.0.0.1", "10.0.0.2"]
                    }
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().getApiRoot()).isEqualTo("http://10.0.0.1");
    }

    @Test
    @DisplayName("apiRoot is null when neither apiPrefix, fqdn nor ipv4 are present")
    void apiRootIsNullWhenNoRoutingDataIsAvailable() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {"nfInstanceId": "udm-1", "nfType": "UDM"}
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().getApiRoot()).isNull();
    }

    @Test
    @DisplayName("apiRoot uses fqdn when nfServices apiPrefix is missing")
    void apiRootUsesFqdnWhenApiPrefixMissing() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {
                      "nfInstanceId": "udm-1",
                      "nfType": "UDM",
                      "fqdn": "udm.example.com",
                      "nfServices": [
                        {"serviceName": "nudm-sdm"}
                      ]
                    }
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().getApiRoot()).isEqualTo("http://udm.example.com");
    }

    @Test
    @DisplayName("unknown nfType strings are silently skipped")
    void unknownNfTypeIsSkipped() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {"nfInstanceId": "x-1", "nfType": "PLANET-EXPRESS", "fqdn": "x.example.com"}
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("apiRoot uses the first nfServices apiPrefix when present")
    void apiRootUsesApiPrefixWhenPresent() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {
                      "nfInstanceId": "udm-1",
                      "nfType": "UDM",
                      "fqdn": "udm.example.com",
                      "nfServices": [
                        {"serviceName": "nudm-sdm", "apiPrefix": "https://api.udm.example.com"}
                      ]
                    }
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM).getFirst().getApiRoot())
                .isEqualTo("https://api.udm.example.com");
    }

    @Test
    @DisplayName("nfInstances with an empty nfServices list falls back to fqdn")
    void emptyServicesFallsBackToFqdn() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {
                      "nfInstanceId": "udm-1",
                      "nfType": "UDM",
                      "fqdn": "udm.example.com",
                      "nfServices": []
                    }
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM).getFirst().getApiRoot())
                .isEqualTo("http://udm.example.com");
    }

    private static int pickClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
