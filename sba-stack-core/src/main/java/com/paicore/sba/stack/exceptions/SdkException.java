package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;
import lombok.Getter;

@Getter
@Generated
public class SdkException extends RuntimeException {

    private final String code;
    private final String details;

    public SdkException(String code, String message) {
        super(message);
        this.code = code;
        this.details = null;
    }

    public SdkException(String code, String message, String details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public SdkException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = cause != null ? cause.getMessage() : null;
    }

    public SdkException(String code, String message, String details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public String describe() {
        return ErrorCodes.describe(code);
    }
}
