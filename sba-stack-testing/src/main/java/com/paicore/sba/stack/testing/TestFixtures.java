package com.paicore.sba.stack.testing;

import com.paicore.sba.stack.codec.MessageType;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.testing.fixtures.JwtFixture;
import com.paicore.sba.stack.testing.fixtures.SmsRecordFixture;
import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import com.paicore.sba.stack.testing.fixtures.UeContextFixture;

/**
 * Entry point that aggregates the testing-module fixtures so downstream
 * applications (and the bundled integration tests) can build realistic 3GPP
 * payloads without re-implementing builders.
 *
 * <p>Each helper produces the same kind of object an operator-grade SMSF
 * would have to construct when consuming the 5G SBA Stack public API.
 */
public final class TestFixtures {

    public static final String DEFAULT_SUPI = "imsi-460000000000001";
    public static final String DEFAULT_GPSI = "msisdn-8613915900000";
    public static final String DEFAULT_PEI = "imei-490154203237518";
    public static final String DEFAULT_CONTENT_ID = "sms";

    private static final Ss7SmsTpduCodec CODEC = new Ss7SmsTpduCodec();

    private TestFixtures() {
        throw new IllegalStateException("Utility class");
    }

    public static UeSmsContextData defaultUeContext() {
        return UeContextFixture.defaultContext();
    }

    public static UeSmsContextData ueContextFor(String supi) {
        return UeContextFixture.contextFor(supi);
    }

    public static RegistrationRequest defaultRegistrationRequest() {
        return RegistrationRequest.builder()
                .correlationId("corr-" + System.nanoTime())
                .ueContext(defaultUeContext())
                .build();
    }

    public static SmsRecordData defaultSmsRecord() {
        return SmsRecordFixture.defaultRecord();
    }

    public static SmsRecordData smsRecordFor(String smsRecordId) {
        return SmsRecordFixture.recordFor(smsRecordId);
    }

    /**
     * Builds a UCS-2 (DCS 0x08) SMS_SUBMIT TPDU. UCS-2 is used by default so
     * the message text appears as readable ASCII (interleaved with {@code 0x00}
     * bytes) in any Wireshark/tcpdump capture — GSM-7 packing would obscure it.
     */
    public static byte[] defaultSubmitTpdu(String message) {
        return TpduFixture.ucs2Submit("+8613915900000", message);
    }

    /** Builds a UCS-2 (DCS 0x08) SMS_DELIVER TPDU — see {@link #defaultSubmitTpdu}. */
    public static byte[] defaultDeliverTpdu(String message) {
        return TpduFixture.encode(MessageType.SMS_DELIVER, "+50212345678",
                message, TpduFixture.DCS_UCS2);
    }

    public static MtMessageRequest defaultMtRequest(String message) {
        return MtMessageRequest.builder()
                .correlationId("corr-" + System.nanoTime())
                .targetSupi(DEFAULT_SUPI)
                .smsRecord(defaultSmsRecord())
                .requestDlr(true)
                .tpduBinary(defaultDeliverTpdu(message))
                .build();
    }

    public static Ss7SmsTpduCodec codec() {
        return CODEC;
    }

    public static JwtFixture jwt() {
        return JwtFixture.shared();
    }
}
