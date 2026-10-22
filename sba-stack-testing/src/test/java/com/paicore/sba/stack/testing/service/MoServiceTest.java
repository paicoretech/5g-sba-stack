package com.paicore.sba.stack.testing.service;

import com.paicore.sba.stack.codec.MessageType;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.controller.dto.DecodedTpdu;
import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import com.paicore.sba.stack.testing.service.model.MoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the SMSF MO business logic — the inputs are already-
 * decoded SmsRecordData + DecodedTpdu, so the test never has to touch
 * multipart parsing or the HTTP layer. Exercises the rules a real SMSF
 * applies when an MO sendsms arrives at the {@code POST /ue-contexts/{supi}/sendsms}
 * endpoint and gets routed to the service layer.
 */
class MoServiceTest {

    private static final Ss7SmsTpduCodec CODEC = new Ss7SmsTpduCodec();

    private RegistrationService registry;
    private MoService service;

    @BeforeEach
    void setUp() {
        registry = new RegistrationService();
        service = new MoService(registry);
        registry.register(TestFixtures.DEFAULT_SUPI, TestFixtures.defaultUeContext());
    }

    @Test
    @DisplayName("registered UE + SMS_SUBMIT TPDU is accepted and captured")
    void happyPathMo() {
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "Hello world");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);
        SmsRecordData smsRecord = TestFixtures.smsRecordFor("rec-mo-1");

        MoResult result = service.processMo(TestFixtures.DEFAULT_SUPI, smsRecord, decoded);

        assertThat(result.accepted()).isTrue();
        assertThat(result.smsRecordId()).isEqualTo("rec-mo-1");
        assertThat(result.tpduType()).isEqualTo("SMS_SUBMIT");
        assertThat(result.decodedMessage()).isEqualTo("Hello world");
        assertThat(service.captures()).hasSize(1);
        assertThat(service.acceptedCount()).isEqualTo(1);
        assertThat(service.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("unregistered UE is rejected with SMSF-4041")
    void rejectsUnregisteredUe() {
        registry.deregister(TestFixtures.DEFAULT_SUPI);

        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "nope");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);

        MoResult result = service.processMo(TestFixtures.DEFAULT_SUPI,
                TestFixtures.smsRecordFor("rec-x"), decoded);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SMSF-4041");
        assertThat(service.captures()).isEmpty();
        assertThat(service.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("MO carrying SMS_DELIVER instead of SMS_SUBMIT is rejected")
    void rejectsWrongMti() {
        byte[] deliver = TpduFixture.encode(MessageType.SMS_DELIVER, "+50212345678",
                "deliver-not-submit", TpduFixture.DCS_UCS2);
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(deliver), deliver);

        MoResult result = service.processMo(TestFixtures.DEFAULT_SUPI,
                TestFixtures.smsRecordFor("rec-x"), decoded);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SMSF-4005");
    }

    @Test
    @DisplayName("null record or tpdu is rejected as invalid payload")
    void rejectsNullInputs() {
        assertThat(service.processMo(TestFixtures.DEFAULT_SUPI, null, null).accepted())
                .isFalse();
        assertThat(service.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("UCS-2 payload preserves the decoded message text through the service")
    void ucs2PayloadRoundTrip() {
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "Hola ñ");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);

        MoResult result = service.processMo(TestFixtures.DEFAULT_SUPI,
                TestFixtures.smsRecordFor("rec-ucs2"), decoded);

        assertThat(result.accepted()).isTrue();
        assertThat(result.decodedMessage()).isEqualTo("Hola ñ");
        assertThat(decoded.dataCodingScheme()).isEqualTo(0x08);
    }

    @Test
    @DisplayName("GSM-7 payload is also accepted (different DCS) and text decodes back")
    void gsm7PayloadRoundTrip() {
        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "Hello world");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);

        MoResult result = service.processMo(TestFixtures.DEFAULT_SUPI,
                TestFixtures.smsRecordFor("rec-gsm7"), decoded);

        assertThat(result.accepted()).isTrue();
        assertThat(result.decodedMessage()).isEqualTo("Hello world");
        assertThat(decoded.dataCodingScheme()).isEqualTo(0x00);
    }

    @Test
    @DisplayName("null record alone is rejected as invalid payload")
    void rejectsNullRecord() {
        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "x");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);
        assertThat(service.processMo(TestFixtures.DEFAULT_SUPI, null, decoded).accepted())
                .isFalse();
        assertThat(service.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear() resets counters and captures")
    void clearResetsState() {
        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "x");
        DecodedTpdu decoded = DecodedTpdu.fromJss7(CODEC.decode(tpdu), tpdu);
        service.processMo(TestFixtures.DEFAULT_SUPI,
                TestFixtures.smsRecordFor("rec-clear"), decoded);
        assertThat(service.acceptedCount()).isEqualTo(1);

        service.clear();

        assertThat(service.captures()).isEmpty();
        assertThat(service.acceptedCount()).isZero();
        assertThat(service.rejectedCount()).isZero();
    }
}
