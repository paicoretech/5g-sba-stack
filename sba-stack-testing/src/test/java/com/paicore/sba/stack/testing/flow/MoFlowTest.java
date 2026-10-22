package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsSubmitTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpduType;

import static org.assertj.core.api.Assertions.assertThat;

class MoFlowTest {

    private MockSbaCoreServer mock;
    private RegistrationFlow registration;
    private MoFlow flow;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        registration = new RegistrationFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.register"));
        flow = new MoFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.mo"));
        registration.register(new RegistrationRequest("corr-bootstrap",
                TestFixtures.defaultUeContext()));
    }

    @AfterEach
    void stop() {
        flow.close();
        registration.close();
        mock.close();
    }

    @Test
    @DisplayName("MO sendsms encodes multipart, the server decodes the TPDU as SMS_SUBMIT and returns 202")
    void happyPathDecodesSubmitTpdu() throws Exception {
        byte[] tpdu = TestFixtures.defaultSubmitTpdu("Hello SBA");
        MoMessageRequest request = MoMessageRequest.builder()
                .correlationId("corr-mo-1")
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();

        MoResponse response = flow.sendMo(request, tpdu);

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getError()).isNull();
        assertThat(mock.getMoCount().get()).isEqualTo(1);
        MockSbaCoreServer.MoCapture capture = mock.getMoCaptures().get(0);
        assertThat(capture.decoded().getSmsTpduType()).isEqualTo(SmsTpduType.SMS_SUBMIT);
        SmsSubmitTpdu submit = (SmsSubmitTpdu) capture.decoded();
        submit.getUserData().decode();
        assertThat(submit.getUserData().getDecodedMessage()).isEqualTo("Hello SBA");
    }

    @Test
    @DisplayName("UCS2 payload preserves non-ASCII characters after server-side TPDU decode")
    void ucs2PayloadRoundtrips() throws Exception {
        byte[] tpdu = com.paicore.sba.stack.testing.fixtures.TpduFixture.ucs2Submit("+50212345678", "Hola ñ");
        MoMessageRequest request = MoMessageRequest.builder()
                .correlationId("corr-mo-ucs2")
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();

        MoResponse response = flow.sendMo(request, tpdu);

        assertThat(response.isAccepted()).isTrue();
        MockSbaCoreServer.MoCapture capture = mock.getMoCaptures().get(0);
        SmsSubmitTpdu submit = (SmsSubmitTpdu) capture.decoded();
        submit.getUserData().decode();
        assertThat(submit.getUserData().getDecodedMessage()).isEqualTo("Hola ñ");
    }

    @Test
    @DisplayName("MO call against a non-registered UE returns SMSF-4041 from the mock")
    void rejectsUnregisteredUe() {
        // Force a clean UDM by stopping the previously bootstrapped registration:
        mock.getRegistry().clear();

        MoResponse response = flow.sendMo(MoMessageRequest.builder()
                .correlationId("corr-mo-unreg")
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build(), TestFixtures.defaultSubmitTpdu("nope"));

        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getError().getCode()).isEqualTo("SMSF-404");
    }

    @Test
    @DisplayName("corrupted TPDU is rejected with a 4xx response from the mock SMSF")
    void rejectsCorruptedTpdu() {
        byte[] garbage = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

        MoResponse response = flow.sendMo(MoMessageRequest.builder()
                .correlationId("corr-mo-bad")
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build(), garbage);

        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getError().getCode()).startsWith("SMSF-");
    }
}
