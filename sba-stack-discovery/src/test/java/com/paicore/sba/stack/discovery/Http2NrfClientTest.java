package com.paicore.sba.stack.discovery;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Http2NrfClientTest {

    private HttpServer server;
    private AtomicReference<String> capturedQuery;
    private AtomicReference<String> capturedPath;
    private AtomicReference<String> responseBody;
    private AtomicReference<Integer> responseStatus;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        capturedQuery = new AtomicReference<>();
        capturedPath = new AtomicReference<>();
        responseBody = new AtomicReference<>("{\"nfInstances\":[]}");
        responseStatus = new AtomicReference<>(200);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RecordingHandler());
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("discoverInstances issues GET with target-nf-type and requester-nf-type query params")
    void issuesDiscoveryRequestWithProperQueryParams() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .requestTimeout(Duration.ofSeconds(2))
                .build());

        client.discoverInstances(NFType.UDM);

        assertThat(capturedQuery.get())
                .contains("target-nf-type=UDM")
                .contains("requester-nf-type=SMSF");
    }

    @Test
    @DisplayName("discoverInstances maps nfInstances JSON to NFProfile list")
    void parsesNfInstances() {
        responseBody.set("""
                {
                  "validityPeriod": 3600,
                  "nfInstances": [
                    {
                      "nfInstanceId": "udm-1",
                      "nfType": "UDM",
                      "fqdn": "udm.example.com",
                      "nfServices": [
                        {"serviceName": "nudm-sdm", "apiPrefix": "http://udm.example.com/nudm-sdm/v2"}
                      ]
                    },
                    {
                      "nfInstanceId": "udm-2",
                      "nfType": "UDM",
                      "fqdn": "udm2.example.com"
                    }
                  ]
                }
                """);

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        List<NFProfile> profiles = client.discoverInstances(NFType.UDM);

        assertThat(profiles).hasSize(2);
        assertThat(profiles.getFirst().getNfInstanceId()).isEqualTo("udm-1");
        assertThat(profiles.get(0).getNfType()).isEqualTo(NFType.UDM);
        assertThat(profiles.get(0).getApiRoot()).isEqualTo("http://udm.example.com/nudm-sdm/v2");
        assertThat(profiles.get(1).getNfInstanceId()).isEqualTo("udm-2");
        assertThat(profiles.get(1).getApiRoot()).isEqualTo("http://udm2.example.com");
    }

    @Test
    @DisplayName("discoverInstances returns empty list when NRF responds non-2xx")
    void returnsEmptyOnErrorStatus() {
        responseStatus.set(503);
        responseBody.set("{}");

        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .build());

        assertThat(client.discoverInstances(NFType.UDM)).isEmpty();
    }

    @Test
    @DisplayName("discoverInstances skips profiles whose nfType is not in the SDK enum")
    void skipsUnknownNfTypes() {
        responseBody.set("""
                {
                  "nfInstances": [
                    {"nfInstanceId": "weird-1", "nfType": "FUTURE_NF_TYPE"},
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
    @DisplayName("discoverInstances honours a custom discoveryPath setting")
    void usesCustomDiscoveryPath() {
        Http2NrfClient client = new Http2NrfClient(NrfDiscoverySettings.builder()
                .nrfBaseUri(baseUri)
                .discoveryPath("/custom-disc/v2/instances")
                .build());

        client.discoverInstances(NFType.UDM);

        assertThat(capturedPath.get()).isEqualTo("/custom-disc/v2/instances");
    }

    private class RecordingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
