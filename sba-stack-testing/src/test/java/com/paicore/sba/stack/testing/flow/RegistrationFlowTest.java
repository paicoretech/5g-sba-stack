package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import com.paicore.sba.stack.testing.mock.RegisteredUe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

class RegistrationFlowTest {

    private MockSbaCoreServer mock;
    private RegistrationFlow flow;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        flow = new RegistrationFlow(mock.baseUri(), mock.issueToken("smsf-app", "smsf.register"));
    }

    @AfterEach
    void stop() {
        flow.close();
        mock.close();
    }

    @Test
    @DisplayName("happy path registers a UE and the mock UDM remembers the AMF context")
    void happyPathRegisters() {
        RegistrationRequest request = TestFixtures.defaultRegistrationRequest();

        RegistrationResponse response = flow.register(request);

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getSessionId()).isNotBlank();
        assertThat(response.getSupi()).isEqualTo(TestFixtures.DEFAULT_SUPI);
        assertThat(mock.getRegistrationCount().get()).isEqualTo(1);
        RegisteredUe stored = mock.getRegistry().get(TestFixtures.DEFAULT_SUPI);
        assertThat(stored).isNotNull();
        assertThat(stored.getContext().getAmfId()).isEqualTo(request.getUeContext().getAmfId());
    }

    @Test
    @DisplayName("invalid token is rejected with normalised SMSF-4010 error and zero registrations")
    void rejectsInvalidToken() {
        try (RegistrationFlow brokenAuth = new RegistrationFlow(mock.baseUri(), "not-a-jwt")) {
            RegistrationResponse response = brokenAuth.register(TestFixtures.defaultRegistrationRequest());

            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getError().getCode()).startsWith("SMSF-");
            assertThat(mock.getRegistrationCount().get()).isZero();
            assertThat(mock.getAuthFailures().get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("10 concurrent registrations succeed under the virtual-thread pool")
    void concurrentRegistrationsScale() {
        int count = 10;
        AtomicInteger ok = new AtomicInteger();

        List<CompletableFuture<RegistrationResponse>> futures = IntStream.range(0, count)
                .mapToObj(i -> flow.registerAsync(RegistrationRequest.builder()
                        .correlationId("corr-" + i)
                        .ueContext(com.paicore.sba.stack.testing.fixtures.UeContextFixture
                                .contextFor("imsi-46000000000" + String.format("%04d", i))).build())
                        .whenComplete((r, e) -> { if (e == null && r.isAccepted()) ok.incrementAndGet(); }))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        await().atMost(ofSeconds(20))
                .untilAsserted(() -> assertThat(mock.getRegistrationCount().get()).isEqualTo(count));
        assertThat(ok.get()).isEqualTo(count);
    }
}
