package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;

@Generated
public class InvalidSupiException extends SdkException {

    public InvalidSupiException(String details) {
        super(ErrorCodes.INVALID_SUPI, "Invalid SUPI", details);
    }
}
