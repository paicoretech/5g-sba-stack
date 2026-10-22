package com.paicore.sba.stack.testing.util;

import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import lombok.extern.slf4j.Slf4j;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsDeliverTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsSubmitTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.UserData;

/**
 * Decodes a raw TPDU via {@link Ss7SmsTpduCodec} (which wraps RestComm jss7)
 * and renders the fields a human cares about (MTI, address, DCS, UDL,
 * decoded message text) as a single line.
 *
 * <p>Lets the runner and the mock server prove server-to-server that the
 * binary attached to a multipart/related sendsms is a syntactically valid
 * 3GPP TS23.040 TPDU — useful when Wireshark only shows the hex dump and the
 * operator wants confirmation that those bytes really do carry the message
 * they were supposed to.
 */
@Slf4j
public final class TpduInspector {

    private static final Ss7SmsTpduCodec CODEC = new Ss7SmsTpduCodec();

    private TpduInspector() {
        throw new IllegalStateException("Utility class");
    }

    public static String inspect(byte[] tpdu) {
        if (tpdu == null || tpdu.length == 0) {
            return "(empty TPDU)";
        }
        try {
            SmsTpdu decoded = CODEC.decode(tpdu);
            return switch (decoded.getSmsTpduType()) {
                case SMS_SUBMIT -> describeSubmit((SmsSubmitTpdu) decoded, tpdu.length);
                case SMS_DELIVER -> describeDeliver((SmsDeliverTpdu) decoded, tpdu.length);
                case SMS_DELIVER_REPORT -> describeDeliverReport(tpdu.length);
                default -> decoded.getSmsTpduType() + " (raw " + tpdu.length + "B)";
            };
        } catch (Exception e) {
            return "(jss7 decode failed: " + e.getMessage() + ")";
        }
    }

    private static String describeSubmit(SmsSubmitTpdu submit, int rawLen) {
        UserData ud = submit.getUserData();
        safeDecode(ud);
        return String.format("SMS_SUBMIT raw=%dB dest=+%s dcs=0x%02x udl=%d text='%s'",
                rawLen,
                submit.getDestinationAddress() == null ? "?" : submit.getDestinationAddress().getAddressValue(),
                ud.getDataCodingScheme().getCode(),
                safeUdl(ud),
                safeText(ud));
    }

    private static String describeDeliver(SmsDeliverTpdu deliver, int rawLen) {
        UserData ud = deliver.getUserData();
        safeDecode(ud);
        return String.format("SMS_DELIVER raw=%dB from=+%s dcs=0x%02x udl=%d text='%s'",
                rawLen,
                deliver.getOriginatingAddress() == null ? "?" : deliver.getOriginatingAddress().getAddressValue(),
                ud.getDataCodingScheme().getCode(),
                safeUdl(ud),
                safeText(ud));
    }

    private static String describeDeliverReport(int rawLen) {
        // When jss7 is told mobileOriginatedPart=true (the codec's default),
        // a network-side SMS_DELIVER byte stream is reported as
        // SMS_DELIVER_REPORT. We still print the raw size so the operator
        // can match it to the wire capture.
        return "SMS_DELIVER_REPORT raw=" + rawLen + "B"
                + " (network SMS_DELIVER decoded under mobile-originated=true)";
    }

    private static void safeDecode(UserData ud) {
        try {
            ud.decode();
        } catch (Exception e) {
            log.debug("UserData.decode() failed: {}", e.toString());
        }
    }

    private static int safeUdl(UserData ud) {
        try { return ud.getDecodedMessage() == null ? 0 : ud.getDecodedMessage().length(); }
        catch (Exception e) { return 0; }
    }

    private static String safeText(UserData ud) {
        try {
            String decoded = ud.getDecodedMessage();
            return decoded == null ? "" : decoded.replace("\n", "\\n");
        } catch (Exception e) {
            return "(decode failed: " + e.getMessage() + ")";
        }
    }
}
