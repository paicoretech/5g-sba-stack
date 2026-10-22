package com.paicore.sba.stack.mo;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.openapi.model.N1MessageClass;
import com.paicore.sba.stack.openapi.model.N1MessageContainer;
import com.paicore.sba.stack.openapi.model.N1N2MessageTransferReqData;
import com.paicore.sba.stack.openapi.model.RefToBinaryData;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.utils.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartRelatedEncoderTest {

    @Test
    @DisplayName("encode emits multipart/related body with JSON + 3GPP SMS parts (TS29540)")
    void encodeProducesExpectedStructure() {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("777c3edf-129f-486e-a3f8-c48e7b515605")
                .gpsi("msisdn-8613915900000")
                .smsPayload(new RefToBinaryData().contentId("sms"));
        byte[] tpdu = {0x11, 0x22, 0x33, (byte) 0xAA, (byte) 0xBB};

        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(smsRecord, tpdu);

        assertThat(encoded.contentType())
                .startsWith("multipart/related; boundary=\"")
                .contains("type=\"application/json\"");

        String body = new String(encoded.body(), StandardCharsets.US_ASCII);
        assertThat(body)
                .contains("Content-Type: application/json")
                .contains("\"smsRecordId\":\"777c3edf-129f-486e-a3f8-c48e7b515605\"")
                .contains("Content-Type: application/vnd.3gpp.sms")
                .contains("Content-Id: sms");
        assertThat(encoded.body()).contains(tpdu);
    }

    @Test
    @DisplayName("encode → decode round-trip preserves record fields and binary TPDU")
    void encodeAndDecodeRoundTrip() {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("rt-1")
                .gpsi("msisdn-1")
                .smsPayload(new RefToBinaryData().contentId("smsRT"));
        byte[] tpdu = {0x01, 0x02, 0x03, (byte) 0x80, 0x7F};

        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(smsRecord, tpdu);
        MoMultipartPayload decoded = MultipartRelatedDecoder.decode(encoded.body(), encoded.contentType());

        assertThat(decoded.getJsonData().getSmsRecordId()).isEqualTo("rt-1");
        assertThat(decoded.getJsonData().getGpsi()).isEqualTo("msisdn-1");
        assertThat(decoded.getContentId()).isEqualTo("smsRT");
        assertThat(decoded.getBinaryData()).containsExactly(tpdu);
    }

    @Test
    @DisplayName("encode rejects a null SmsRecordData")
    void encodeRejectsNullRecord() {
        SdkException ex = assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(null, new byte[]{1}));
        assertEquals(ErrorCodes.INVALID_MT_PAYLOAD, ex.getCode());
    }

    @Test
    @DisplayName("encode rejects null or empty TPDU")
    void encodeRejectsMissingTpdu() {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("rec")
                .smsPayload(new RefToBinaryData().contentId("sms"));
        assertThrows(SdkException.class, () -> MultipartRelatedEncoder.encode(smsRecord, null));
        assertThrows(SdkException.class, () -> MultipartRelatedEncoder.encode(smsRecord, new byte[0]));
    }

    @Test
    @DisplayName("encode rejects record whose smsPayload.contentId is blank")
    void encodeRejectsBlankContentId() {
        SmsRecordData blankContentId = new SmsRecordData()
                .smsRecordId("rec")
                .smsPayload(new RefToBinaryData().contentId(" "));
        assertThrows(SdkException.class, () -> MultipartRelatedEncoder.encode(blankContentId, new byte[]{1}));
    }

    @Test
    @DisplayName("encode emits multipart/related body for the N1N2 request root part (TS29518)")
    void encodeProducesExpectedStructureForN1N2() {
        N1N2MessageTransferReqData request = new N1N2MessageTransferReqData()
                .supi("imsi-001010000000001")
                .ppi(1)
                .n1n2FailureTxfNotifURI("https://smsf.local/namf-cb/v1/4/n1n2-failure")
                .n1MessageContainer(new N1MessageContainer()
                        .n1MessageClass(N1MessageClass.SMS)
                        .n1MessageContent(new RefToBinaryData().contentId("n1-sms-payload")));
        byte[] tpdu = {0x44, 0x55, (byte) 0xCC};

        MultipartRelatedEncoder.Encoded encoded =
                MultipartRelatedEncoder.encode(request, "n1-sms-payload", tpdu);

        assertThat(encoded.contentType())
                .startsWith("multipart/related; boundary=\"")
                .contains("type=\"application/json\"");

        String body = new String(encoded.body(), StandardCharsets.US_ASCII);
        assertThat(body)
                .contains("Content-Type: application/json")
                .contains("\"supi\":\"imsi-001010000000001\"")
                .contains("\"n1MessageClass\":\"SMS\"")
                .contains("\"n1n2FailureTxfNotifURI\"")
                .contains("Content-Type: application/vnd.3gpp.sms")
                .contains("Content-Id: n1-sms-payload");
        assertThat(encoded.body()).contains(tpdu);
    }

    @Test
    @DisplayName("the generic encode rejects a null root part")
    void genericEncodeRejectsNullRoot() {
        SdkException ex = assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(null, "n1-sms-payload", new byte[]{0x01}));
        assertEquals(ErrorCodes.INVALID_MT_PAYLOAD, ex.getCode());
    }

    @Test
    @DisplayName("the generic encode rejects a blank contentId")
    void genericEncodeRejectsBlankContentId() {
        N1N2MessageTransferReqData request = new N1N2MessageTransferReqData().supi("imsi-001010000000001");

        assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(request, "  ", new byte[]{0x01}));
        assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(request, null, new byte[]{0x01}));
    }

    @Test
    @DisplayName("the generic encode rejects a missing TPDU")
    void genericEncodeRejectsMissingTpdu() {
        N1N2MessageTransferReqData request = new N1N2MessageTransferReqData().supi("imsi-001010000000001");

        assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(request, "n1-sms-payload", new byte[0]));
        assertThrows(SdkException.class,
                () -> MultipartRelatedEncoder.encode(request, "n1-sms-payload", null));
    }
}
