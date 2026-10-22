package com.paicore.sba.stack.exceptions;

import lombok.Generated;

@Generated
public class InvalidTpduException extends SdkException {

    public static final String CODE = "SMSF-5104";

    public InvalidTpduException(String details) {
        super(CODE, "Invalid TPDU", details);
    }

    public InvalidTpduException(String details, Throwable cause) {
        super(CODE, "Invalid TPDU", details, cause);
    }
}
