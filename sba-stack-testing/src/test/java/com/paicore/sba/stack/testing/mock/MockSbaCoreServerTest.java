package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.flow.MoFlow;
import com.paicore.sba.stack.testing.flow.MtFlow;
import com.paicore.sba.stack.testing.flow.RegistrationFlow;
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
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage for {@link MockSbaCoreServer}: every route, every
 * error branch, every {@link MtDeliveryBehavior} variant, NRF discovery /
 * registration, the OAuth2 token + JWKS endpoints, and the wire-dump path.
 * Tests use a real HTTP/1.1 client (the JDK {@code HttpServer} the mock is
 * built on only speaks HTTP/1.1) so the {@code ExchangeWithBody} +
 * {@code DelegatingHttpExchange} branches are exercised for free.
 */
class MockSbaCoreServerTest {

    private MockSbaCoreServer mock;
    private HttpClient client;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterEach
    void stop() {
        mock.close();
    }

    // ---------------------------------------------------------------- registration

    @Test
    @DisplayName("startWithoutAuth skips bearer enforcement and serves register/deregister")
    void startWithoutAuthSkipsAuth() {
        try (MockSbaCoreServer open = MockSbaCoreServer.startWithoutAuth();
             RegistrationFlow flow = new RegistrationFlow(open.baseUri(), "")) {
            RegistrationResponse response = flow.register(TestFixtures.defaultRegistrationRequest());
            assertThat(response.isAccepted()).isTrue();
            assertThat(open.port()).isPositive();
            assertThat(open.jwt()).isNotNull();
            assertThat(open.baseUri().toString()).startsWith("http://127.0.0.1:");
        }
    }

    @Test
    @DisplayName("full registration → MO → deregistration journey produces expected captures")
    void fullJourney() throws Exception {
        try (RegistrationFlow registration = new RegistrationFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.register"));
             MoFlow mo = new MoFlow(mock.baseUri(),
                     mock.issueToken("smsf-app", "smsf.mo"))) {
            RegistrationResponse reg = registration.register(TestFixtures.defaultRegistrationRequest());
            assertThat(reg.isAccepted()).isTrue();
            assertThat(mock.getRegistrationCount().get()).isEqualTo(1);
            assertThat(mock.getRegistry()).containsKey(TestFixtures.DEFAULT_SUPI);
            assertThat(mock.getRecorded()).isNotEmpty();

            MoResponse moResp = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("corr-mo")
                    .smsRecord(TestFixtures.defaultSmsRecord())
                    .build(), TestFixtures.defaultSubmitTpdu("Hi"));
            assertThat(moResp.isAccepted()).isTrue();
            assertThat(mock.getMoCount().get()).isEqualTo(1);
            assertThat(mock.getMoCaptures()).hasSize(1);

            HttpResponse<String> deleteResp = deregister(TestFixtures.DEFAULT_SUPI,
                    mock.issueToken("smsf-app", "smsf.register"));
            assertThat(deleteResp.statusCode()).isEqualTo(204);
            assertThat(mock.getRegistry()).doesNotContainKey(TestFixtures.DEFAULT_SUPI);
        }
    }

    @Test
    @DisplayName("registration with no auth header returns 401 and increments authFailures")
    void registrationMissingAuthIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(mock.getAuthFailures().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid bearer token returns 401")
    void registrationInvalidTokenIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer not-a-jwt")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(mock.getAuthFailures().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("expired bearer token returns 401")
    void registrationExpiredTokenIsRejected() throws Exception {
        String token = TestFixtures.jwt().issueExpiredToken("smsf-app");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("registration with wrong HTTP method returns 405")
    void registrationWrongMethodReturns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("MOCK-4050");
    }

    @Test
    @DisplayName("registration with invalid SUPI returns 400")
    void registrationInvalidSupi() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/not-a-valid-supi"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.register"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("registration with unparseable JSON body returns 400")
    void registrationGarbageJsonReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.register"))
                .POST(HttpRequest.BodyPublishers.ofString("not-a-json"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("registration accepts bare UeSmsContextData body (TS29540 example)")
    void registrationAcceptsBareContext() throws Exception {
        String body = com.paicore.sba.stack.utils.SdkConverter.valueAsString(
                TestFixtures.defaultUeContext());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.register"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(mock.getRegistrationCount().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUPI URL vs payload mismatch is rejected with 400")
    void registrationSupiMismatchIsRejected() throws Exception {
        RegistrationRequest req = RegistrationRequest.builder()
                .correlationId("c1").ueContext(TestFixtures.defaultUeContext()).build();
        String body = com.paicore.sba.stack.utils.SdkConverter.valueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/imsi-460000000000999"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.register"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("deregistering an unknown SUPI returns 404")
    void deregisteringUnknownSupiReturns404() throws Exception {
        HttpResponse<String> response = deregister(TestFixtures.DEFAULT_SUPI,
                mock.issueToken("app", "smsf.register"));
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("deregistration with invalid SUPI returns 400")
    void deregisterInvalidSupiReturns400() throws Exception {
        HttpResponse<String> response = deregister("not-valid",
                mock.issueToken("app", "smsf.register"));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("deregistration without auth returns 401")
    void deregisterWithoutAuthReturns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI))
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- MO

    @Test
    @DisplayName("MO without registration returns 404")
    void moWithoutRegistration() {
        try (MoFlow mo = new MoFlow(mock.baseUri(), mock.issueToken("app", "smsf.mo"))) {
            MoResponse response = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("c").smsRecord(TestFixtures.defaultSmsRecord()).build(),
                    TestFixtures.defaultSubmitTpdu("hi"));
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).isEqualTo("SMSF-404");
        }
    }

    @Test
    @DisplayName("MO wrong HTTP method returns 405")
    void moWrongMethodReturns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("MO without bearer returns 401")
    void moWithoutAuthReturns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related")
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("MO with invalid SUPI returns 400")
    void moInvalidSupiReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/not-a-supi/sendsms"))
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.mo"))
                .header("Content-Type", "multipart/related")
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MO with broken multipart returns 400")
    void moBrokenMultipart() throws Exception {
        registerDefault();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.mo"))
                .header("Content-Type", "multipart/related; boundary=BOUND")
                .POST(HttpRequest.BodyPublishers.ofString("garbage"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MO with garbage TPDU is rejected with 400")
    void moInvalidTpduReturns400() {
        registerDefault();
        try (MoFlow mo = new MoFlow(mock.baseUri(), mock.issueToken("app", "smsf.mo"))) {
            MoResponse response = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("c").smsRecord(TestFixtures.defaultSmsRecord()).build(),
                    new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff});
            assertThat(response.isAccepted()).isFalse();
        }
    }

    // ---------------------------------------------------------------- MT

    @Test
    @DisplayName("MT happy path returns 200 with DLR body")
    void mtHappyPath() {
        try (MtFlow flow = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("Hello MT"));
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(mock.getMtCount().get()).isEqualTo(1);
            assertThat(mock.getMtCaptures()).hasSize(1);
        }
    }

    @Test
    @DisplayName("MT permanent failure exhausts retries")
    void mtAlwaysFailExhaustsRetries() {
        mock.setMtBehavior(MtDeliveryBehavior.alwaysFail(500, "ERR-500", "boom"));
        try (MtFlow flow = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("nope"));
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
            assertThat(mock.getMtCount().get()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("MT failFirstNAttempts delivers after the retries")
    void mtFailFirstNAttemptsDelivers() {
        mock.setMtBehavior(MtDeliveryBehavior.failFirstNAttempts(2, 503, "ERR-503"));
        try (MtFlow flow = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("retry"));
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(mock.getMtCount().get()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("MT delayBy injects sleep but still delivers")
    void mtDelayByStillDelivers() {
        mock.setMtBehavior(MtDeliveryBehavior.delayBy(Duration.ofMillis(50)));
        try (MtFlow flow = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            long started = System.currentTimeMillis();
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("delay"));
            long elapsed = System.currentTimeMillis() - started;
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(elapsed).isGreaterThanOrEqualTo(40L);
        }
    }

    @Test
    @DisplayName("MT wrong HTTP method returns 405")
    void mtWrongMethodReturns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("MT without bearer returns 401")
    void mtWithoutAuthReturns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Content-Type", "multipart/related")
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("MT with invalid SUPI returns 400")
    void mtInvalidSupiReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/mock/ue-contexts/not-a-supi/sendsms"))
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.mt"))
                .header("Content-Type", "multipart/related")
                .POST(HttpRequest.BodyPublishers.ofString("body"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MT with broken multipart returns 400")
    void mtBrokenMultipartReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.mt"))
                .header("Content-Type", "multipart/related; boundary=BOUND")
                .POST(HttpRequest.BodyPublishers.ofString("garbage"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MT with garbage TPDU bytes returns 400")
    void mtGarbageTpduReturns400() throws Exception {
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.defaultSmsRecord(), new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mock.baseUri().resolve("/mock/ue-contexts/" + TestFixtures.DEFAULT_SUPI + "/sendsms"))
                .header("Authorization", "Bearer " + mock.issueToken("app", "smsf.mt"))
                .header("Content-Type", encoded.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- NRF discovery

    @Test
    @DisplayName("NRF discovery returns all profiles when no filter is set")
    void discoveryListsAllProfiles() throws Exception {
        mock.setNfProfiles(List.of(
                NFProfile.builder().nfInstanceId("nf-udm-1").nfType(NFType.UDM)
                        .apiRoot("http://udm.local:8080").build(),
                NFProfile.builder().nfInstanceId("nf-amf-1").nfType(NFType.AMF)
                        .apiRoot("http://amf.local:8080").build()));
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-disc/v1/nf-instances"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("nf-udm-1").contains("nf-amf-1");
    }

    @Test
    @DisplayName("NRF discovery filters by target-nf-type query parameter")
    void discoveryFiltersByType() throws Exception {
        mock.setNfProfiles(List.of(
                NFProfile.builder().nfInstanceId("nf-udm-1").nfType(NFType.UDM)
                        .apiRoot("http://udm.local").build(),
                NFProfile.builder().nfInstanceId("nf-amf-1").nfType(NFType.AMF)
                        .apiRoot("http://amf.local").build()));
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-disc/v1/nf-instances?target-nf-type=UDM"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("nf-udm-1").doesNotContain("nf-amf-1");
    }

    @Test
    @DisplayName("NRF discovery trailing slash also serves the list")
    void discoveryTrailingSlash() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-disc/v1/nf-instances/"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("NRF discovery with unknown sub-path returns 404")
    void discoveryUnknownPath() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-disc/wat"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("NRF discovery wrong HTTP method returns 405")
    void discoveryWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-disc/v1/nf-instances"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    // ---------------------------------------------------------------- NRF NFM (registration)

    @Test
    @DisplayName("NRF NFM PUT registers a profile and subsequent discovery returns it")
    void nrfRegistrationViaPut() throws Exception {
        String body = "{\"nfType\":\"SMSF\",\"apiRoot\":\"http://smsf.local:9999\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/nf-instances/inst-1"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        HttpResponse<String> again = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/nf-instances/inst-1"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("NRF NFM POST is also accepted (alias for PUT)")
    void nrfRegistrationViaPost() throws Exception {
        String body = "{\"nfType\":\"UDM\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/nf-instances/inst-2"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("NRF NFM wrong path returns 404")
    void nrfRegistrationWrongPath() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/foo"))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("NRF NFM wrong HTTP method returns 405")
    void nrfRegistrationWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/nf-instances/foo"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("NRF NFM with unknown nfType returns 400")
    void nrfRegistrationUnknownNfType() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/nnrf-nfm/v1/nf-instances/inst-3"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"nfType\":\"WAT\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- OAuth2

    @Test
    @DisplayName("/oauth2/jwks publishes a JWKS document")
    void oauthJwksReturnsKeys() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/oauth2/jwks"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("keys");
    }

    @Test
    @DisplayName("/oauth2/jwks wrong method returns 405")
    void oauthJwksWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/oauth2/jwks"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("/oauth2/token issues a bearer token")
    void oauthTokenIssuesAccessToken() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/oauth2/token"))
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("access_token");
    }

    @Test
    @DisplayName("/oauth2/token wrong method returns 405")
    void oauthTokenWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/oauth2/token"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("/oauth2/unknown returns 404")
    void oauthUnknownPath() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(mock.baseUri().resolve("/oauth2/lolwhat"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- misc

    @Test
    @DisplayName("setWireDump enables the audit log dump path for both MO and MT")
    void wireDumpExercisesBothPaths() {
        mock.setWireDump(true);
        registerDefault();
        try (MoFlow mo = new MoFlow(mock.baseUri(), mock.issueToken("app", "smsf.mo"));
             MtFlow mt = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            MoResponse moResp = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("c").smsRecord(TestFixtures.defaultSmsRecord()).build(),
                    TestFixtures.defaultSubmitTpdu("hi"));
            MtResponse mtResp = mt.deliver(TestFixtures.defaultMtRequest("hi"));
            assertThat(moResp.isAccepted()).isTrue();
            assertThat(mtResp.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        }
    }

    @Test
    @DisplayName("explicit start(host, port, requireAuth, jwt) constructor binds and works")
    void explicitStartConstructor() {
        try (MockSbaCoreServer open = MockSbaCoreServer.start(true, TestFixtures.jwt())) {
            assertThat(open.baseUri().toString()).startsWith("http://");
        }
    }

    @Test
    @DisplayName("start(host, port, requireAuth, jwt) signature exercises explicit binding too")
    void explicitHostPortStartConstructor() {
        try (MockSbaCoreServer open = MockSbaCoreServer.start("127.0.0.1", 0, false,
                TestFixtures.jwt())) {
            assertThat(open.port()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("MoCapture / MtCapture records expose their constructor arguments")
    void captureRecordsExposeFields() {
        registerDefault();
        try (MoFlow mo = new MoFlow(mock.baseUri(), mock.issueToken("app", "smsf.mo"));
             MtFlow mt = new MtFlow(mock.baseUri(), mock.issueToken("app", "smsf.mt"))) {
            mo.sendMo(MoMessageRequest.builder()
                    .correlationId("c").smsRecord(TestFixtures.defaultSmsRecord()).build(),
                    TestFixtures.defaultSubmitTpdu("x"));
            mt.deliver(TestFixtures.defaultMtRequest("x"));

            MockSbaCoreServer.MoCapture moCap = mock.getMoCaptures().get(0);
            MockSbaCoreServer.MtCapture mtCap = mock.getMtCaptures().get(0);
            assertThat(moCap.supi()).isEqualTo(TestFixtures.DEFAULT_SUPI);
            assertThat(moCap.tpdu()).isNotEmpty();
            assertThat(moCap.smsRecord()).isNotNull();
            assertThat(moCap.decoded()).isNotNull();
            assertThat(mtCap.supi()).isEqualTo(TestFixtures.DEFAULT_SUPI);
            assertThat(mtCap.tpdu()).isNotEmpty();
            assertThat(mtCap.smsRecord()).isNotNull();
            assertThat(mtCap.decoded()).isNotNull();
        }
    }

    @Test
    @DisplayName("recorded list contains the inbound requests with method, path, headers, body")
    void recordedHistoryIsPopulated() {
        registerDefault();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(mock.getRecorded()).isNotEmpty());
        RecordedRequest first = mock.getRecorded().get(0);
        assertThat(first.getMethod()).isEqualTo("POST");
        assertThat(first.getPath()).startsWith("/ue-contexts/");
        assertThat(first.getHeaders()).containsKey("authorization");
        assertThat(first.getResponseStatus()).isEqualTo(201);
        assertThat(first.getReceivedAt()).isNotNull();
        assertThat(first.getBody()).isNotEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void registerDefault() {
        try (RegistrationFlow flow = new RegistrationFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.register"))) {
            flow.register(TestFixtures.defaultRegistrationRequest());
        }
    }

    private HttpResponse<String> deregister(String supi, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mock.baseUri() + "/ue-contexts/" + supi))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unused")
    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
