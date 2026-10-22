package com.paicore.sba.stack.testing.functional;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.flow.DiscoveryFlow;
import com.paicore.sba.stack.testing.flow.MoFlow;
import com.paicore.sba.stack.testing.flow.MtFlow;
import com.paicore.sba.stack.testing.flow.RegistrationFlow;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import com.paicore.sba.stack.testing.simulator.MtTrafficSimulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator-grade functional test: walks the entire SMSF journey end-to-end
 * against the mock 5G core, in the order an operator network would do it.
 *
 * <p>Steps:
 * <ol>
 *   <li>NRF discovery returns the seeded UDM/AMF instances.</li>
 *   <li>UE registers and the mock UDM remembers the AMF context.</li>
 *   <li>An MO sendsms is encoded as multipart/related, decoded by the mock
 *       SMSF, TPDU type asserted as SMS_SUBMIT.</li>
 *   <li>A single MT delivery succeeds.</li>
 *   <li>A burst of 50 MTs is dispatched concurrently — all delivered.</li>
 * </ol>
 *
 * <p>The class binds the mock to a fixed port {@code 0} (ephemeral) on
 * {@code 0.0.0.0} so traffic is on a real socket; flip the constant below
 * to {@code 127.0.0.1} for loopback-only when capturing on {@code lo}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullJourneyFunctionalTest {

    private static final String BIND_HOST = "0.0.0.0";
    private static final int BIND_PORT = 0;

    private MockSbaCoreServer mock;
    private String token;
    private URI baseUri;

    @BeforeAll
    void bootstrap() {
        mock = MockSbaCoreServer.start(BIND_HOST, BIND_PORT, true, TestFixtures.jwt());
        baseUri = URI.create("http://127.0.0.1:" + mock.port());
        token = mock.issueToken("functional-it", "smsf.register", "smsf.mo", "smsf.mt");
        mock.setNfProfiles(List.of(
                NFProfile.builder().nfInstanceId("udm-eastus-1").nfType(NFType.UDM)
                        .apiRoot("http://udm-eastus-1.local/nudm-sdm/v2").build(),
                NFProfile.builder().nfInstanceId("amf-eastus-1").nfType(NFType.AMF)
                        .apiRoot("http://amf-eastus-1.local/namf-comm/v1").build()
        ));
    }

    @AfterAll
    void teardown() {
        mock.close();
    }

    @Test
    @Order(1)
    @DisplayName("step 1: NRF discovery returns seeded UDM and AMF instances")
    void step1Discovery() {
        DiscoveryFlow discovery = new DiscoveryFlow(baseUri);

        assertThat(discovery.resolveAll(NFType.UDM)).hasSize(1);
        assertThat(discovery.resolveAll(NFType.AMF)).hasSize(1);
    }

    @Test
    @Order(2)
    @DisplayName("step 2: UE registers and the mock UDM keeps the AMF context")
    void step2Register() {
        try (RegistrationFlow reg = new RegistrationFlow(baseUri, token)) {
            RegistrationResponse resp = reg.register(TestFixtures.defaultRegistrationRequest());

            assertThat(resp.isAccepted()).isTrue();
            assertThat(resp.getSessionId()).isNotBlank();
            assertThat(mock.getRegistry()).containsKey(TestFixtures.DEFAULT_SUPI);
        }
    }

    @Test
    @Order(3)
    @DisplayName("step 3: MO multipart sendsms decodes server-side and TPDU type is SMS_SUBMIT")
    void step3MoSendSms() {
        try (MoFlow mo = new MoFlow(baseUri, token)) {
            MoResponse resp = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("corr-mo-functional")
                    .smsRecord(TestFixtures.defaultSmsRecord())
                    .build(), TestFixtures.defaultSubmitTpdu("functional MO"));

            assertThat(resp.isAccepted()).isTrue();
            MockSbaCoreServer.MoCapture last = mock.getMoCaptures().get(mock.getMoCaptures().size() - 1);
            assertThat(last.decoded().getSmsTpduType().name()).isEqualTo("SMS_SUBMIT");
        }
    }

    @Test
    @Order(4)
    @DisplayName("step 4: single MT delivery is captured by the mock receiver")
    void step4MtSingle() {
        try (MtFlow mt = new MtFlow(baseUri, token)) {
            MtResponse resp = mt.deliver(TestFixtures.defaultMtRequest("functional MT"));

            assertThat(resp.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(mock.getMtCount().get()).isPositive();
        }
    }

    @Test
    @Order(5)
    @DisplayName("step 5: 5-message MT burst delivers under the virtual-thread pool")
    void step5MtBurst() {
        int n = 5;
        try (MtFlow mt = new MtFlow(baseUri, token);
             MtTrafficSimulator sim = new MtTrafficSimulator(mt)) {

            List<MtMessageRequest> burst = IntStream.range(0, n)
                    .mapToObj(i -> MtMessageRequest.builder()
                            .correlationId("corr-burst-" + i)
                            .targetSupi(TestFixtures.DEFAULT_SUPI)
                            .smsRecord(TestFixtures.smsRecordFor("rec-" + i))
                            .tpduBinary(TestFixtures.defaultDeliverTpdu("Burst " + i))
                            .requestDlr(true).build())
                    .toList();

            MtTrafficSimulator.Result result = sim.runConcurrent(burst, Duration.ofSeconds(15));

            assertThat(result.getDelivered()).isEqualTo(n);
            assertThat(result.getFailed()).isZero();
            assertThat(result.successRate()).isEqualTo(1.0);
        }
    }

    @Test
    @Order(6)
    @DisplayName("step 6: mock environment recorded every wire-level call across the journey")
    void step6Trace() {
        assertThat(mock.getRegistrationCount().get()).isGreaterThanOrEqualTo(1);
        assertThat(mock.getMoCount().get()).isGreaterThanOrEqualTo(1);
        assertThat(mock.getMtCount().get()).isGreaterThanOrEqualTo(6); // 1 single + 5 burst
        assertThat(mock.getRecorded()).isNotEmpty();
        assertThat(mock.getAuthFailures().get()).isZero();
    }
}
