package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;

@Generated
public class InvalidPeiException extends SdkException {

    public InvalidPeiException(String details) {
        super(ErrorCodes.INVALID_PEI, "Invalid PEI", details);
    }
}
