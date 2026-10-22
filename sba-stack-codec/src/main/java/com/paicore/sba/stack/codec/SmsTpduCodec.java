package com.paicore.sba.stack.codec;

import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;

/**
 * Codec abstraction for 3GPP TS23.040 SMS TPDUs. Implementations must be
 * stateless and thread-safe. The default implementation is
 * {@link Ss7SmsTpduCodec}, built on top of RestComm jss7 internally.
 */
public interface SmsTpduCodec {

    /**
     * Decodes a raw TPDU. The returned value is a RestComm {@link SmsTpdu}
     * whose runtime type matches the TPDU type — callers can cast to
     * {@code SmsSubmitTpdu}, {@code SmsDeliverTpdu}, {@code SmsStatusReportTpdu}
     * or {@code SmsCommandTpdu} based on {@link SmsTpdu#getSmsTpduType()}.
     */
    SmsTpdu decode(byte[] tpduBinary);

    byte[] encode(EncodeTpduRequest request);
}
