package com.paicore.sba.stack.testing.controller.dto;

import com.paicore.sba.stack.codec.SmsTpduCodec;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsDeliverTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsSubmitTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.UserData;

import java.util.Arrays;
import java.util.Objects;

/**
 * Typed view of a single 3GPP TS23.040 TPDU after it has been decoded by the
 * stack's {@link SmsTpduCodec}. Services receive instances of this record so
 * they never have to deal with raw byte arrays or jss7 implementation classes.
 *
 * @param rawBytes        the original wire bytes (kept for auditing / retransmit)
 * @param mti             {@code SMS_SUBMIT}, {@code SMS_DELIVER}, {@code SMS_DELIVER_REPORT}, …
 * @param dataCodingScheme TP-DCS as the integer the wire carries (0x00 = GSM-7, 0x08 = UCS-2)
 * @param address         originating address (DELIVER) or destination address (SUBMIT), without TON prefix
 * @param decodedMessage  human-readable message body after the appropriate alphabet has been applied
 * @param tpdu            the jss7 instance — kept so services that need protocol-level fields can downcast
 */
public record DecodedTpdu(
        byte[] rawBytes,
        String mti,
        int dataCodingScheme,
        String address,
        String decodedMessage,
        SmsTpdu tpdu) {

    public static DecodedTpdu fromJss7(SmsTpdu tpdu, byte[] rawBytes) {
        if (tpdu instanceof SmsSubmitTpdu submit) {
            return fromSubmit(submit, rawBytes);
        }
        if (tpdu instanceof SmsDeliverTpdu deliver) {
            return fromDeliver(deliver, rawBytes);
        }
        return new DecodedTpdu(
                rawBytes,
                tpdu.getSmsTpduType().name(),
                -1,
                null,
                null,
                tpdu);
    }

    private static DecodedTpdu fromSubmit(SmsSubmitTpdu submit, byte[] rawBytes) {
        UserData ud = submit.getUserData();
        String text = decodeMessage(ud);
        int dcs = ud.getDataCodingScheme() == null ? -1 : ud.getDataCodingScheme().getCode();
        String addr = submit.getDestinationAddress() == null ? null
                : submit.getDestinationAddress().getAddressValue();
        return new DecodedTpdu(rawBytes, "SMS_SUBMIT", dcs, addr, text, submit);
    }

    private static DecodedTpdu fromDeliver(SmsDeliverTpdu deliver, byte[] rawBytes) {
        UserData ud = deliver.getUserData();
        String text = decodeMessage(ud);
        int dcs = ud.getDataCodingScheme() == null ? -1 : ud.getDataCodingScheme().getCode();
        String addr = deliver.getOriginatingAddress() == null ? null
                : deliver.getOriginatingAddress().getAddressValue();
        return new DecodedTpdu(rawBytes, "SMS_DELIVER", dcs, addr, text, deliver);
    }

    private static String decodeMessage(UserData ud) {
        if (ud == null) {
            return null;
        }
        try {
            ud.decode();
            return ud.getDecodedMessage();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecodedTpdu other)) return false;
        return dataCodingScheme == other.dataCodingScheme
                && Arrays.equals(rawBytes, other.rawBytes)
                && Objects.equals(mti, other.mti)
                && Objects.equals(address, other.address)
                && Objects.equals(decodedMessage, other.decodedMessage)
                && Objects.equals(tpdu, other.tpdu);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(mti, dataCodingScheme, address, decodedMessage, tpdu)
                + Arrays.hashCode(rawBytes);
    }

    @Override
    public String toString() {
        return "DecodedTpdu[mti=" + mti
                + ", dataCodingScheme=" + dataCodingScheme
                + ", address=" + address
                + ", decodedMessage=" + decodedMessage
                + ", rawBytes=" + Arrays.toString(rawBytes) + "]";
    }
}
