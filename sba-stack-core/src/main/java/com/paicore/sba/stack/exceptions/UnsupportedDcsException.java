package com.paicore.sba.stack.exceptions;

import lombok.Generated;

@Generated
public class UnsupportedDcsException extends SdkException {

    public static final String CODE = "SMSF-5103";

    public UnsupportedDcsException(int dcs) {
        super(CODE, "Unsupported DCS", String.format("0x%02X", dcs));
    }
}
