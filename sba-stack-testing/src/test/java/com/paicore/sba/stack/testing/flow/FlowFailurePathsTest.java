package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the IOException / non-2xx / DELETE / async fallback paths of the
 * flow classes against a deliberately unreachable URL and against the live
 * mock. These error-path tests round out the coverage of the flow layer.
 */
class FlowFailurePathsTest {

    /** Returns a {@code http://127.0.0.1:freePort/} URI whose socket is closed. */
    private static URI unreachableBaseUri() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            int port = s.getLocalPort();
            return URI.create("http://127.0.0.1:" + port + "/");
        }
    }

    @Test
    @DisplayName("RegistrationFlow.register returns SMSF transport failure on IOException")
    void registerSurfacesIoFailure() throws Exception {
        try (RegistrationFlow flow = new RegistrationFlow(unreachableBaseUri(), "tok")) {
            RegistrationResponse response = flow.register(TestFixtures.defaultRegistrationRequest());
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).isEqualTo("SMSF-5001");
        }
    }

    @Test
    @DisplayName("RegistrationFlow.deregister returns false when the target is unreachable")
    void deregisterFalseOnIoFailure() throws Exception {
        try (RegistrationFlow flow = new RegistrationFlow(unreachableBaseUri(), "tok")) {
            assertThat(flow.deregister("imsi-460000000000001")).isFalse();
        }
    }

    @Test
    @DisplayName("RegistrationFlow.deregister returns true after a successful registration")
    void deregisterHappyPath() {
        try (MockSbaCoreServer mock = MockSbaCoreServer.startWithDefaults()) {
            try (RegistrationFlow flow = new RegistrationFlow(mock.baseUri(),
                    mock.issueToken("smsf-app", "smsf.register"))) {
                flow.register(TestFixtures.defaultRegistrationRequest());
                assertThat(flow.deregister(TestFixtures.DEFAULT_SUPI)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("RegistrationFlow.registerAsync completes asynchronously via the executor")
    void registerAsyncCompletes() {
        try (MockSbaCoreServer mock = MockSbaCoreServer.startWithDefaults()) {
            try (RegistrationFlow flow = new RegistrationFlow(mock.baseUri(),
                    mock.issueToken("smsf-app", "smsf.register"))) {
                RegistrationResponse response = flow.registerAsync(TestFixtures.defaultRegistrationRequest()).join();
                assertThat(response.isAccepted()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("RegistrationFlow.register surfaces a SMSF-<status> error on non-2xx mock")
    void registerSurfacesNon2xx() {
        try (MockSbaCoreServer mock = MockSbaCoreServer.startWithDefaults()) {
            try (RegistrationFlow flow = new RegistrationFlow(mock.baseUri(), "not-a-jwt")) {
                RegistrationResponse response = flow.register(TestFixtures.defaultRegistrationRequest());
                assertThat(response.isAccepted()).isFalse();
                assertThat(response.getError().getCode()).startsWith("SMSF-");
                assertThat(response.getError().getDetails()).contains("Mock NRF/UDM responded");
            }
        }
    }

    @Test
    @DisplayName("MoFlow.sendMo returns INVALID_MO_PAYLOAD when record is missing")
    void moRejectsMissingRecord() {
        try (MoFlow flow = new MoFlow(URI.create("http://127.0.0.1:1/"), "tok")) {
            MoResponse response = flow.sendMo(MoMessageRequest.builder()
                    .correlationId("corr-no-record").build(), new byte[]{1, 2, 3});
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).isEqualTo("SMSF-4005");
        }
    }

    @Test
    @DisplayName("MoFlow.sendMo returns transport failure on IOException")
    void moSurfacesIoFailure() throws Exception {
        try (MoFlow flow = new MoFlow(unreachableBaseUri(), "tok")) {
            MoResponse response = flow.sendMo(MoMessageRequest.builder()
                    .correlationId("corr-mo-unreach")
                    .smsRecord(TestFixtures.defaultSmsRecord())
                    .build(), TpduFixture.gsm7Submit("+50212345678", "x"));
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).isEqualTo("SMSF-5001");
        }
    }

    @Test
    @DisplayName("MoFlow.sendMoAsync resolves with a transport failure when target is unreachable")
    void moAsyncFailsClean() throws Exception {
        try (MoFlow flow = new MoFlow(unreachableBaseUri(), "tok")) {
            MoResponse response = flow.sendMoAsync(MoMessageRequest.builder()
                    .correlationId("corr-mo-async")
                    .smsRecord(TestFixtures.defaultSmsRecord())
                    .build(), TpduFixture.gsm7Submit("+50212345678", "x"))
                    .join();
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).isEqualTo("SMSF-5001");
        }
    }

    @Test
    @DisplayName("MtFlow.deliver returns INVALID_MT_PAYLOAD when record is missing")
    void mtRejectsMissingRecord() {
        try (MtFlow flow = new MtFlow(URI.create("http://127.0.0.1:1/"), "tok")) {
            MtResponse response = flow.deliver(MtMessageRequest.builder()
                    .correlationId("corr-mt-empty")
                    .targetSupi(TestFixtures.DEFAULT_SUPI)
                    .build());
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
            assertThat(response.getError().getCode()).isEqualTo("SMSF-4006");
        }
    }

    @Test
    @DisplayName("MtFlow.deliver exhausts retries on an unreachable target")
    void mtExhaustsRetriesOnIoFailure() throws Exception {
        try (MtFlow flow = new MtFlow(unreachableBaseUri(), "tok",
                2, java.time.Duration.ofMillis(5))) {
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("Will fail"));
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
            assertThat(response.getError().getCode()).isEqualTo("SMSF-5001");
            assertThat(flow.inFlight()).isZero();
        }
    }

    @Test
    @DisplayName("MtFlow constructor sanitises non-positive maxAttempts to 1")
    void mtNormalisesMaxAttempts() throws Exception {
        try (MtFlow flow = new MtFlow(unreachableBaseUri(), "tok", 0, java.time.Duration.ZERO)) {
            MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("once"));
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
        }
    }

    @Test
    @DisplayName("MtFlow.deliverAsync completes via the executor on the unreachable path too")
    void mtAsyncResolvesOnFailure() throws Exception {
        try (MtFlow flow = new MtFlow(unreachableBaseUri(), "tok",
                1, java.time.Duration.ZERO)) {
            MtResponse response = flow.deliverAsync(TestFixtures.defaultMtRequest("a")).join();
            assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
        }
    }
}
