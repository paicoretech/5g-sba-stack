package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.testing.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Coverage suite for {@link Http2MockServer} — the standalone h2c
 * prior-knowledge Netty server. The JDK HTTP/2 client sends an HTTP/1.1
 * Upgrade request which is incompatible with this server (it speaks h2
 * prior-knowledge only). We therefore use a Netty client that issues an
 * H2 preface; instead, we drive it indirectly through the test scenarios
 * exposed by the SBA stack itself (the {@link com.paicore.sba.stack.testing.flow.MtFlow}
 * already uses the JDK client which negotiates h2c upgrade). To keep this
 * test independent of upgrade choreography, we drive the server with the
 * same JDK HttpClient but force {@code HTTP_2} — the Netty multiplex pipeline
 * handles the h2c upgrade dance internally because the codec accepts the
 * upgrade. Routes covered: happy-path sendsms, unknown route 404, broken
 * multipart 400, accessors.
 */
class Http2MockServerTest {

    private Http2MockServer mock;
    private HttpClient client;

    @BeforeEach
    void start() {
        mock = Http2MockServer.startCleartext();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterEach
    void stop() {
        mock.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + mock.port() + path);
    }

    @Test
    @DisplayName("port + initial counters are exposed and zeroed at startup")
    void initialMetrics() {
        assertThat(mock.port()).isPositive();
        assertThat(mock.requestCount()).isZero();
        assertThat(mock.errorCount()).isZero();
        assertThat(mock.captures()).isEmpty();
    }

    @Test
    @DisplayName("POST /sendsms with valid multipart/TPDU returns 204 and records a Capture")
    void happyPathReturns204() throws Exception {
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultDeliverTpdu("Hello"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(uri("/nsmsf-sms/v2/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(mock.requestCount()).isEqualTo(1);
            assertThat(mock.captures()).hasSize(1);
        });
        Http2MockServer.Capture capture = mock.captures().get(0);
        assertThat(capture.path()).contains("/sendsms");
        assertThat(capture.tpduType()).contains("SMS_");
        assertThat(capture.bodyLength()).isGreaterThan(0);
        assertThat(capture.smsRecordId()).isNotBlank();
        // Streams are incremented at least once
        assertThat(mock.streamCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET on /sendsms returns 404 (route is POST-only)")
    void wrongMethodReturns404() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(uri("/nsmsf-sms/v2/ue-contexts/foo/sendsms"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST to unknown route returns 404")
    void unknownRouteReturns404() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(uri("/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("malformed multipart body returns 400 and increments errorCount")
    void brokenMultipartReturns400() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(uri("/nsmsf-sms/v2/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related; boundary=B")
                .POST(HttpRequest.BodyPublishers.ofString("garbage"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(mock.errorCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("Capture record exposes its fields via accessors")
    void captureRecordAccessors() {
        Http2MockServer.Capture capture =
                new Http2MockServer.Capture("/p", "id-1", "SMS_DELIVER", 42);
        assertThat(capture.path()).isEqualTo("/p");
        assertThat(capture.smsRecordId()).isEqualTo("id-1");
        assertThat(capture.tpduType()).isEqualTo("SMS_DELIVER");
        assertThat(capture.bodyLength()).isEqualTo(42);
    }
}
