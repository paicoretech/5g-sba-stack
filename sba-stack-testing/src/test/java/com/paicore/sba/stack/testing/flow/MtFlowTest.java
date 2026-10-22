package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import com.paicore.sba.stack.testing.mock.MtDeliveryBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MtFlowTest {

    private MockSbaCoreServer mock;
    private MtFlow flow;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        flow = new MtFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.mt"));
    }

    @AfterEach
    void stop() {
        flow.close();
        mock.close();
    }

    @Test
    @DisplayName("MT delivery succeeds and the receiver captures the TPDU type")
    void happyPathDelivers() {
        MtMessageRequest request = TestFixtures.defaultMtRequest("Hello MT");

        MtResponse response = flow.deliver(request);

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(response.getError()).isNull();
        assertThat(mock.getMtCount().get()).isEqualTo(1);
        // The codec is configured with mobile-originated=true (see Ss7SmsTpduCodec),
        // so a wire-format SMS_DELIVER frame decodes as SMS_DELIVER_REPORT on the
        // mobile side. Either MTI is acceptable — both share the TS23.040 §9.2.3.1
        // first-octet pattern.
        assertThat(mock.getMtCaptures().get(0).decoded().getSmsTpduType().name())
                .isIn("SMS_DELIVER", "SMS_DELIVER_REPORT");
    }

    @Test
    @DisplayName("retry engine recovers when the first two attempts fail with a transient 503")
    void retriesUntilDelivered() {
        mock.setMtBehavior(MtDeliveryBehavior.failFirstNAttempts(2, 503, "SMSF-5002"));

        MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("Retry me"));

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(mock.getMtCount().get()).isEqualTo(3);
    }

    @Test
    @DisplayName("permanent failure exhausts retries and surfaces UNDELIVERABLE")
    void permanentFailure() {
        mock.setMtBehavior(MtDeliveryBehavior.alwaysFail(500, "SMSF-5002", "permanent"));

        MtResponse response = flow.deliver(TestFixtures.defaultMtRequest("Will fail"));

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
        assertThat(response.getError().getCode()).isEqualTo("SMSF-5002");
        assertThat(mock.getMtCount().get()).isEqualTo(3);
    }

    @Test
    @DisplayName("invalid request (missing TPDU) is rejected before the wire call")
    void missingTpduRejectedLocally() {
        MtResponse response = flow.deliver(MtMessageRequest.builder()
                .correlationId("corr-mt-empty")
                .targetSupi(TestFixtures.DEFAULT_SUPI)
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build());

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(mock.getMtCount().get()).isZero();
    }

    @Test
    @DisplayName("async delivery returns a CompletableFuture that resolves with DELIVERED")
    void asyncDelivery() {
        MtResponse response = flow.deliverAsync(TestFixtures.defaultMtRequest("Async"))
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(mock.getMtCount().get()).isEqualTo(1));
    }
}
