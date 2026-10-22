package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;

@Generated
public class InvalidGpsiException extends SdkException {

    public InvalidGpsiException(String details) {
        super(ErrorCodes.INVALID_GPSI, "Invalid GPSI", details);
    }
}
