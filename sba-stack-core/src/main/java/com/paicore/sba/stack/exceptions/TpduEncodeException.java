package com.paicore.sba.stack.exceptions;

import lombok.Generated;

@Generated
public class TpduEncodeException extends SdkException {

    public static final String CODE = "SMSF-5102";

    public TpduEncodeException(String details) {
        super(CODE, "TPDU encode failed", details);
    }

    public TpduEncodeException(String details, Throwable cause) {
        super(CODE, "TPDU encode failed", details, cause);
    }
}
