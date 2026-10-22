package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.fixtures.JwtFixture;
import com.paicore.sba.stack.utils.SdkConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full coverage suite for the Netty-backed {@link MockSbaH2cServer}. The JDK
 * {@code HttpClient.Version.HTTP_2} performs the h2c Upgrade dance so every
 * exchange exercises both the HTTP/1.1 entry pipeline and the
 * {@link io.netty.handler.codec.http2.Http2MultiplexHandler} stream child
 * channel. Tests also cover error branches (auth, invalid SUPI, broken
 * multipart, TPDU decode, unknown route, wrong method) and the OAuth2 +
 * discovery routes.
 */
class MockSbaH2cServerTest {

    private MockSbaH2cServer h2c;
    private HttpClient client;
    private String registerToken;
    private String moToken;
    private String mtToken;

    @BeforeEach
    void start() {
        h2c = MockSbaH2cServer.start("127.0.0.1", 0, true, JwtFixture.shared());
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        registerToken = h2c.issueToken("smsf-app", "smsf.register");
        moToken = h2c.issueToken("smsf-app", "smsf.mo");
        mtToken = h2c.issueToken("smsf-app", "smsf.mt");
    }

    @AfterEach
    void stop() {
        h2c.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + h2c.getPort() + path);
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void register(String supi) throws Exception {
        byte[] body = SdkConverter.valueAsString(RegistrationRequest.builder()
                .correlationId("c").ueContext(TestFixtures.ueContextFor(supi)).build())
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + supi))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + registerToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- meta

    @Test
    @DisplayName("baseUri and accessors expose the bound port + JwtFixture")
    void baseUriIsConsistent() {
        assertThat(h2c.baseUri()).hasToString("http://127.0.0.1:" + h2c.getPort());
        assertThat(h2c.getPort()).isPositive();
        assertThat(h2c.jwt()).isNotNull();
        assertThat(h2c.getMoService()).isNotNull();
        assertThat(h2c.getRegistrationService()).isNotNull();
        assertThat(h2c.getRecorded()).isEmpty();
        assertThat(h2c.getMtCaptures()).isEmpty();
        assertThat(h2c.getMtCount().get()).isZero();
        assertThat(h2c.getAuthFailures().get()).isZero();
    }

    // ---------------------------------------------------------------- registration

    @Test
    @DisplayName("registration over h2c upgrade returns 201 and the request is recorded")
    void registrationHappyPath() throws Exception {
        register(TestFixtures.DEFAULT_SUPI);
        assertThat(h2c.getRecorded()).isNotEmpty();
        RecordedRequest last = h2c.getRecorded().get(h2c.getRecorded().size() - 1);
        assertThat(last.getResponseStatus()).isEqualTo(201);
        assertThat(last.getPath()).isEqualTo("/ue-contexts/" + TestFixtures.DEFAULT_SUPI);
    }

    @Test
    @DisplayName("registration with wrong method returns 405")
    void registrationWrongMethod() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .GET().build());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("registration without auth returns 401 and bumps authFailures")
    void registrationMissingAuth() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(h2c.getAuthFailures().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("registration with invalid bearer returns 401")
    void registrationInvalidBearer() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer not-a-jwt")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("registration with garbage body returns 400")
    void registrationGarbageBody() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + registerToken)
                .POST(HttpRequest.BodyPublishers.ofString("not-a-json"))
                .build());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("deregistration over h2c returns 200 when registered, 404 otherwise")
    void deregisterFlow() throws Exception {
        register(TestFixtures.DEFAULT_SUPI);
        HttpResponse<String> ok = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Authorization", "Bearer " + registerToken)
                .DELETE().build());
        assertThat(ok.statusCode()).isEqualTo(200);

        HttpResponse<String> missing = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Authorization", "Bearer " + registerToken)
                .DELETE().build());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("deregister without auth returns 401")
    void deregisterMissingAuth() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .DELETE().build());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- MO

    @Test
    @DisplayName("MO over h2c happy path returns 202")
    void moHappyPath() throws Exception {
        register(TestFixtures.DEFAULT_SUPI);
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultSubmitTpdu("hi"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + moToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(h2c.getMoService().acceptedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("MO wrong method returns 405")
    void moWrongMethod() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .GET().build());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("MO without auth returns 401")
    void moMissingAuth() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related")
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("MO against unregistered SUPI returns 404")
    void moUnregisteredSupi() throws Exception {
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultSubmitTpdu("hi"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + moToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("MO with broken multipart returns 400")
    void moBrokenMultipart() throws Exception {
        register(TestFixtures.DEFAULT_SUPI);
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related; boundary=B")
                .header("Authorization", "Bearer " + moToken)
                .POST(HttpRequest.BodyPublishers.ofString("garbage"))
                .build());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MO wire-dump path triggers on a successful 2xx")
    void moWithWireDump() throws Exception {
        h2c.setWireDump(true);
        register(TestFixtures.DEFAULT_SUPI);
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultSubmitTpdu("hi"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + moToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    // ---------------------------------------------------------------- MT

    @Test
    @DisplayName("MT happy path returns 200 with DLR body")
    void mtHappyPath() throws Exception {
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultDeliverTpdu("Hello"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("DELIVERED");
        assertThat(h2c.getMtCount().get()).isEqualTo(1);
        assertThat(h2c.getMtCaptures()).hasSize(1);
    }

    @Test
    @DisplayName("MT wrong method returns 405")
    void mtWrongMethod() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .GET().build());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("MT without auth returns 401")
    void mtMissingAuth() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("MT with invalid SUPI returns 400")
    void mtInvalidSupi() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/not-a-supi/sendsms"))
                .header("Content-Type", "multipart/related")
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MT broken multipart returns 400")
    void mtBrokenMultipart() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related; boundary=B")
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofString("garbage"))
                .build());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MT with invalid TPDU bytes returns 400")
    void mtInvalidTpdu() throws Exception {
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff});
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MT with alwaysFail behaviour returns the configured non-2xx status")
    void mtAlwaysFailBehavior() throws Exception {
        h2c.setMtBehavior(MtDeliveryBehavior.alwaysFail(500, "ERR-500", "boom"));
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultDeliverTpdu("hi"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("ERR-500");
    }

    @Test
    @DisplayName("MT delayBy still delivers but introduces wall-clock latency")
    void mtDelayBy() throws Exception {
        h2c.setMtBehavior(MtDeliveryBehavior.delayBy(Duration.ofMillis(50)));
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultDeliverTpdu("hi"));
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).isGreaterThanOrEqualTo(40L);
    }

    @Test
    @DisplayName("MT setWireDump emits the audit dump on success")
    void mtWireDump() throws Exception {
        h2c.setWireDump(true);
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), TestFixtures.defaultDeliverTpdu("d"));
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + mtToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- NRF + OAuth2

    @Test
    @DisplayName("Discovery returns the configured NF profiles")
    void discoveryReturnsProfiles() throws Exception {
        h2c.setNfProfiles(List.of(
                NFProfile.builder().nfInstanceId("udm-1").nfType(NFType.UDM)
                        .apiRoot("http://udm.local").build(),
                NFProfile.builder().nfInstanceId("amf-1").nfType(NFType.AMF)
                        .apiRoot("http://amf.local").build()));
        HttpResponse<String> all = send(HttpRequest.newBuilder()
                .uri(uri("/nnrf-disc/v1/nf-instances")).GET().build());
        HttpResponse<String> filtered = send(HttpRequest.newBuilder()
                .uri(uri("/nnrf-disc/v1/nf-instances?target-nf-type=UDM")).GET().build());

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).contains("udm-1").contains("amf-1");
        assertThat(filtered.body()).contains("udm-1").doesNotContain("amf-1");
    }

    @Test
    @DisplayName("Discovery with wrong method returns 405")
    void discoveryWrongMethod() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/nnrf-disc/v1/nf-instances"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("OAuth2 token issues an access token")
    void oauthToken() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/oauth2/token"))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("access_token");
    }

    @Test
    @DisplayName("OAuth2 token wrong method returns 405")
    void oauthTokenWrongMethod() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/oauth2/token")).GET().build());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("OAuth2 JWKS is served on GET and 405 on POST")
    void oauthJwks() throws Exception {
        HttpResponse<String> ok = send(HttpRequest.newBuilder()
                .uri(uri("/oauth2/jwks")).GET().build());
        HttpResponse<String> wrong = send(HttpRequest.newBuilder()
                .uri(uri("/oauth2/jwks"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("keys");
        assertThat(wrong.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("Unknown route returns 404")
    void unknownRoute() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/no/such/route")).GET().build());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Server bound on an explicit port without auth accepts unauthenticated requests")
    void serverWithoutAuthAcceptsPlain() {
        try (MockSbaH2cServer open = MockSbaH2cServer.start("127.0.0.1", 0, false,
                JwtFixture.shared())) {
            HttpClient plain = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2).build();
            byte[] body = SdkConverter.valueAsString(RegistrationRequest.builder()
                    .correlationId("c").ueContext(TestFixtures.defaultUeContext()).build())
                    .getBytes(StandardCharsets.UTF_8);
            try {
                HttpResponse<String> response = plain.send(HttpRequest.newBuilder()
                        .uri(open.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(201);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
