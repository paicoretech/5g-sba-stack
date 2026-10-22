package com.paicore.sba.stack.security.exceptions;

import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.exceptions.SdkException;
import lombok.Generated;
import lombok.Getter;

@Generated
@Getter
public class AuthenticationException extends SdkException {

    public static final String CODE = "SMSF-4010";
    private static final String MESSAGE = "Authentication failed";

    private final SecurityFailureCause failureCause;

    public AuthenticationException(String details) {
        this(details, (SecurityFailureCause) null);
    }

    public AuthenticationException(String details, Throwable cause) {
        super(CODE, MESSAGE, details, cause);
        this.failureCause = null;
    }

    public AuthenticationException(String details, SecurityFailureCause failureCause) {
        super(CODE, MESSAGE, details);
        this.failureCause = failureCause;
    }

    public AuthenticationException(String details, SecurityFailureCause failureCause, Throwable cause) {
        super(CODE, MESSAGE, details, cause);
        this.failureCause = failureCause;
    }
}
