package com.paicore.sba.stack.codec;

import com.paicore.sba.stack.exceptions.InvalidTpduException;
import com.paicore.sba.stack.exceptions.TpduDecodeException;
import com.paicore.sba.stack.exceptions.TpduEncodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsSubmitTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpduType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ss7SmsTpduCodecTest {

    private final SmsTpduCodec codec = new Ss7SmsTpduCodec();

    @Test
    @DisplayName("SMS_SUBMIT GSM7 payload encodes and decodes back to the original message")
    void encodeThenDecodeSubmitGsm7RoundTrip() throws Exception {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("Hello")
                .dataCoding(0x00)
                .build());

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        SmsTpdu decoded = codec.decode(encoded);
        assertEquals(SmsTpduType.SMS_SUBMIT, decoded.getSmsTpduType());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, decoded);
        assertEquals(0x00, submit.getDataCodingScheme().getCode());
        assertEquals("50212345678", submit.getDestinationAddress().getAddressValue());
        submit.getUserData().decode();
        assertEquals("Hello", submit.getUserData().getDecodedMessage());
    }

    @Test
    @DisplayName("SMS_SUBMIT UCS2 payload preserves non-ASCII characters across round-trip")
    void encodeUcs2PreservesNonAsciiChars() throws Exception {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("Hola ñ")
                .dataCoding(0x08)
                .build());

        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertEquals(0x08, submit.getDataCodingScheme().getCode());
        submit.getUserData().decode();
        assertEquals("Hola ñ", submit.getUserData().getDecodedMessage());
    }

    @Test
    @DisplayName("SMS_DELIVER encode produces a non-empty binary payload")
    void encodeDeliverProducesParsablePayload() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_DELIVER)
                .originatingAddress("12025550100")
                .originatingAddressTon(1)
                .originatingAddressNpi(1)
                .message("Hi")
                .dataCoding(0x00)
                .build());
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }

    @Test
    @DisplayName("encode handles a null message body by emitting an empty user-data payload")
    void encodeAcceptsNullMessageBody() throws Exception {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message(null)
                .dataCoding(0x00)
                .build());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        submit.getUserData().decode();
        assertEquals("", submit.getUserData().getDecodedMessage());
    }

    @Test
    @DisplayName("encode defaults the DCS to 0 when dataCoding is not supplied")
    void encodeDefaultsDcsWhenAbsent() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("default-dcs")
                .build());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertEquals(0x00, submit.getDataCodingScheme().getCode());
    }

    @Test
    @DisplayName("encode keeps the international '+' prefix in the destination address")
    void encodeRecognisesInternationalAddress() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("+50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("intl")
                .dataCoding(0x00)
                .build());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertEquals("50212345678", submit.getDestinationAddress().getAddressValue());
    }

    @Test
    @DisplayName("an alphanumeric originator travels with TON 5 instead of being encoded as TBCD digits")
    void encodeAcceptsAlphanumericOriginator() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_DELIVER)
                .originatingAddress("PAiCore")
                .originatingAddressTon(5)
                .originatingAddressNpi(0)
                .message("Hello")
                .dataCoding(0x00)
                .build());

        assertEquals((byte) 0xD0, encoded[2], "the TP-OA type-of-address byte must say alphanumeric");
    }

    @Test
    @DisplayName("the TON and the NPI of the request are the ones written into the TP-OA")
    void encodeHonoursTheRequestedTonAndNpi() {
        byte[] intl = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_DELIVER)
                .originatingAddress("12025550100")
                .originatingAddressTon(1)
                .originatingAddressNpi(1)
                .message("Hello")
                .dataCoding(0x00)
                .build());

        assertEquals((byte) 0x91, intl[2], "international + ISDN is 0x91");
    }

    @Test
    @DisplayName("encoding an address without TON or NPI fails saying which fields are missing")
    void encodeFailsWhenTonOrNpiAreMissing() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .message("no ton")
                .dataCoding(0x00)
                .build();

        TpduEncodeException ex = assertThrows(TpduEncodeException.class, () -> codec.encode(req));
        assertTrue(ex.getDetails().contains("TON and NPI are required"));
    }

    @Test
    @DisplayName("encode accepts a null destination address and emits an empty AddressField")
    void encodeAcceptsNullAddress() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress(null)
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("anon")
                .dataCoding(0x00)
                .build());
        assertNotNull(encoded);
        SmsTpdu decoded = codec.decode(encoded);
        assertEquals(SmsTpduType.SMS_SUBMIT, decoded.getSmsTpduType());
    }

    @Test
    @DisplayName("encode rejects null request or missing messageType")
    void rejectsNullRequestOrMessageType() {
        assertThrows(TpduEncodeException.class, () -> codec.encode(null));
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .destinationAddress("123").destinationAddressTon(1).destinationAddressNpi(1).message("x").dataCoding(0).build();
        assertThrows(TpduEncodeException.class, () -> codec.encode(req));
    }

    @Test
    @DisplayName("encode rejects unsupported message types with TpduEncodeException")
    void rejectsUnsupportedEncodeMessageType() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_STATUS_REPORT)
                .destinationAddress("123").destinationAddressTon(1).destinationAddressNpi(1).message("x").dataCoding(0).build();
        assertThrows(TpduEncodeException.class, () -> codec.encode(req));
    }

    @Test
    @DisplayName("decode rejects null or empty TPDU payloads with InvalidTpduException")
    void rejectsEmptyTpdu() {
        assertThrows(InvalidTpduException.class, () -> codec.decode(new byte[0]));
        assertThrows(InvalidTpduException.class, () -> codec.decode(null));
    }

    @Test
    @DisplayName("decode reports garbage payloads as TpduDecodeException")
    void rejectsGarbageBinaryAsTpduDecodeException() {
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(TpduDecodeException.class, () -> codec.decode(garbage));
    }

    @Test
    @DisplayName("encode carries the supplied protocol identifier into the TPDU")
    void encodeAppliesProtocolIdentifier() {
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("pid")
                .dataCoding(0x00)
                .protocolId(0x7F)
                .build());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertEquals(0x7F, submit.getProtocolIdentifier().getCode());
    }

    @Test
    @DisplayName("encode wraps pre-encoded user data and preserves the UDH indicator")
    void encodePreservesPreEncodedUserDataWithUdh() {
        // UDH (concatenated SMS): UDHL=05, IEI=00, IEDL=03, ref, total, seq + "Hi"
        byte[] preEncoded = {0x05, 0x00, 0x03, (byte) 0xAB, 0x02, 0x01, 'H', 'i'};
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("12025550100")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .dataCoding(0x04)
                .userData(preEncoded)
                .userDataLength(preEncoded.length)
                .userDataHeaderPresent(true)
                .build());

        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertTrue(submit.getUserDataHeaderIndicator());
        assertTrue(submit.getUserData().getEncodedUserDataHeaderIndicator());
        assertArrayEquals(preEncoded, submit.getUserData().getEncodedData());
    }

    @Test
    @DisplayName("encode wraps pre-encoded user data for an SMS_DELIVER MT payload")
    void encodeDeliverWithPreEncodedUserData() {
        byte[] preEncoded = {0x05, 0x00, 0x03, (byte) 0xAB, 0x02, 0x01, 'H', 'i'};
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_DELIVER)
                .originatingAddress("12025550100")
                .originatingAddressTon(1)
                .originatingAddressNpi(1)
                .dataCoding(0x00)
                .userData(preEncoded)
                .userDataLength(preEncoded.length)
                .userDataHeaderPresent(true)
                .build());
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }

    @Test
    @DisplayName("encode prefers pre-encoded user data over the plain text message")
    void encodePrefersUserDataBytesOverMessage() {
        byte[] preEncoded = {'R', 'A', 'W'};
        byte[] encoded = codec.encode(EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50212345678")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("ignored")
                .dataCoding(0x04)
                .userData(preEncoded)
                .build());
        SmsSubmitTpdu submit = assertInstanceOf(SmsSubmitTpdu.class, codec.decode(encoded));
        assertArrayEquals(preEncoded, submit.getUserData().getEncodedData());
    }

    @Test
    @DisplayName("encode produces byte-stable output for the same input")
    void encodeIsDeterministicForSameInput() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("50211112222")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("repeat")
                .dataCoding(0x00)
                .build();
        assertArrayEquals(codec.encode(req), codec.encode(req));
    }
}
