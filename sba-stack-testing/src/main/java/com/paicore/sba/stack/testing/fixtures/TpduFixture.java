package com.paicore.sba.stack.testing.fixtures;

import com.paicore.sba.stack.codec.EncodeTpduRequest;
import com.paicore.sba.stack.codec.MessageType;
import com.paicore.sba.stack.codec.SmsTpduCodec;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;

/**
 * Builds realistic TPDU binaries via {@link Ss7SmsTpduCodec}. Useful when
 * mocks or tests need to attach the {@code application/vnd.3gpp.sms} part
 * of a sendsms multipart payload.
 */
public final class TpduFixture {

    public static final int DCS_GSM7 = 0x00;
    public static final int DCS_UCS2 = 0x08;

    /** What a plain international MSISDN carries, which is what every fixture address is. */
    public static final int TON_INTERNATIONAL = 1;
    public static final int NPI_ISDN = 1;

    private static final SmsTpduCodec CODEC = new Ss7SmsTpduCodec();

    private TpduFixture() {
        throw new IllegalStateException("Utility class");
    }

    public static byte[] encode(MessageType type, String address, String message) {
        return encode(type, address, message, DCS_GSM7);
    }

    public static byte[] encode(MessageType type, String address, String message, int dcs) {
        return encode(type, address, message, dcs, TON_INTERNATIONAL, NPI_ISDN);
    }

    public static byte[] encode(MessageType type, String address, String message, int dcs, int ton, int npi) {
        EncodeTpduRequest.EncodeTpduRequestBuilder builder = EncodeTpduRequest.builder()
                .messageType(type)
                .message(message)
                .dataCoding(dcs);
        if (type == MessageType.SMS_DELIVER) {
            builder.originatingAddress(address)
                    .originatingAddressTon(ton)
                    .originatingAddressNpi(npi);
        } else {
            builder.destinationAddress(address)
                    .destinationAddressTon(ton)
                    .destinationAddressNpi(npi);
        }
        return CODEC.encode(builder.build());
    }

    public static byte[] gsm7Submit(String destination, String message) {
        return encode(MessageType.SMS_SUBMIT, destination, message, DCS_GSM7);
    }

    public static byte[] ucs2Submit(String destination, String message) {
        return encode(MessageType.SMS_SUBMIT, destination, message, DCS_UCS2);
    }

    public static byte[] gsm7Deliver(String originator, String message) {
        return encode(MessageType.SMS_DELIVER, originator, message, DCS_GSM7);
    }
}
