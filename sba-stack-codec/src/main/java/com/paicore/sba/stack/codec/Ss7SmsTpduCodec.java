package com.paicore.sba.stack.codec;

import com.paicore.sba.stack.exceptions.InvalidTpduException;
import com.paicore.sba.stack.exceptions.TpduDecodeException;
import com.paicore.sba.stack.exceptions.TpduEncodeException;
import com.paicore.sba.stack.exceptions.UnsupportedDcsException;
import lombok.extern.slf4j.Slf4j;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.smstpdu.AddressField;
import org.restcomm.protocols.ss7.map.api.smstpdu.DataCodingScheme;
import org.restcomm.protocols.ss7.map.api.smstpdu.NumberingPlanIdentification;
import org.restcomm.protocols.ss7.map.api.smstpdu.ProtocolIdentifier;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.TypeOfNumber;
import org.restcomm.protocols.ss7.map.api.smstpdu.UserData;
import org.restcomm.protocols.ss7.map.smstpdu.AbsoluteTimeStampImpl;
import org.restcomm.protocols.ss7.map.smstpdu.AddressFieldImpl;
import org.restcomm.protocols.ss7.map.smstpdu.DataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.smstpdu.ProtocolIdentifierImpl;
import org.restcomm.protocols.ss7.map.smstpdu.SmsDeliverTpduImpl;
import org.restcomm.protocols.ss7.map.smstpdu.SmsSubmitTpduImpl;
import org.restcomm.protocols.ss7.map.smstpdu.SmsTpduImpl;
import org.restcomm.protocols.ss7.map.smstpdu.UserDataImpl;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Slf4j
public class Ss7SmsTpduCodec implements SmsTpduCodec {

    private final Charset gsm7Charset;

    public Ss7SmsTpduCodec() {
        this(StandardCharsets.ISO_8859_1);
    }

    public Ss7SmsTpduCodec(Charset gsm7Charset) {
        this.gsm7Charset = gsm7Charset;
    }

    @Override
    public SmsTpdu decode(byte[] tpduBinary) {
        if (tpduBinary == null || tpduBinary.length == 0) {
            throw new InvalidTpduException("TPDU payload is empty");
        }
        try {
            return SmsTpduImpl.createInstance(tpduBinary, true, gsm7Charset);
        } catch (MAPException e) {
            throw new TpduDecodeException("jss7 failed to parse TPDU", e);
        } catch (TpduDecodeException | UnsupportedDcsException | InvalidTpduException e) {
            throw e;
        } catch (Exception e) {
            throw new TpduDecodeException("Unexpected error while decoding TPDU", e);
        }
    }

    @Override
    public byte[] encode(EncodeTpduRequest request) {
        if (request == null || request.getMessageType() == null) {
            throw new TpduEncodeException("EncodeTpduRequest and messageType are required");
        }
        int dcs = request.getDataCoding() == null ? 0x00 : request.getDataCoding();
        try {
            DataCodingScheme scheme = new DataCodingSchemeImpl(dcs);
            ProtocolIdentifier pid = new ProtocolIdentifierImpl(
                    request.getProtocolId() == null ? 0 : request.getProtocolId());
            UserData userData = buildUserData(request, scheme);

            return switch (request.getMessageType()) {
                case SMS_DELIVER -> new SmsDeliverTpduImpl(
                        false, false, false, false,
                        addr(request.getOriginatingAddress(),
                                request.getOriginatingAddressTon(),
                                request.getOriginatingAddressNpi()),
                        pid,
                        new AbsoluteTimeStampImpl(0, 0, 0, 0, 0, 0, 0),
                        userData
                ).encodeData();
                case SMS_SUBMIT -> new SmsSubmitTpduImpl(
                        false, false, false, 0,
                        addr(request.getDestinationAddress(),
                                request.getDestinationAddressTon(),
                                request.getDestinationAddressNpi()),
                        pid, null, userData
                ).encodeData();
                default -> throw new TpduEncodeException(
                        "Encoding for " + request.getMessageType() + " is not supported yet");
            };
        } catch (MAPException e) {
            throw new TpduEncodeException("jss7 failed to encode TPDU", e);
        } catch (TpduEncodeException | UnsupportedDcsException e) {
            throw e;
        } catch (Exception e) {
            throw new TpduEncodeException("Unexpected error while encoding TPDU", e);
        }
    }

    /**
     * Builds the TPDU user-data element. When the caller supplies pre-encoded
     * user-data bytes (e.g. a UDH-carrying or binary payload already serialized
     * upstream) they are wrapped as-is, preserving the user-data-header
     * indicator and length; otherwise the plain text {@code message} is encoded
     * according to the supplied data coding scheme.
     */
    private UserData buildUserData(EncodeTpduRequest request, DataCodingScheme scheme) {
        byte[] encoded = request.getUserData();
        if (encoded != null) {
            int udl = request.getUserDataLength() == null ? encoded.length : request.getUserDataLength();
            return new UserDataImpl(encoded, scheme, udl, request.isUserDataHeaderPresent(), gsm7Charset);
        }
        return new UserDataImpl(
                request.getMessage() == null ? "" : request.getMessage(),
                scheme, null, gsm7Charset);
    }

    private AddressField addr(String value, Integer ton, Integer npi) {
        if (value == null) {
            return new AddressFieldImpl(TypeOfNumber.Unknown,
                    NumberingPlanIdentification.Unknown, "");
        }
        if (ton == null || npi == null) {
            throw new TpduEncodeException("TON and NPI are required to encode the address '" + value + "'");
        }
        boolean international = value.startsWith("+");
        String digits = international ? value.substring(1) : value;

        return new AddressFieldImpl(
                TypeOfNumber.getInstance(ton),
                NumberingPlanIdentification.getInstance(npi),
                digits);
    }
}
