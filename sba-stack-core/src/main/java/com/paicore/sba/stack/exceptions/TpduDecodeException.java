package com.paicore.sba.stack.exceptions;

import lombok.Generated;

@Generated
public class TpduDecodeException extends SdkException {

    public static final String CODE = "SMSF-5101";

    public TpduDecodeException(String details) {
        super(CODE, "TPDU decode failed", details);
    }

    public TpduDecodeException(String details, Throwable cause) {
        super(CODE, "TPDU decode failed", details, cause);
    }
}
