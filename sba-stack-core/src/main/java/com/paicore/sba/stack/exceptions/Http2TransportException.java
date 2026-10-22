package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;

@Generated
public class Http2TransportException extends SdkException {

    public Http2TransportException(String details) {
        super(ErrorCodes.HTTP2_TRANSPORT_FAILURE, "HTTP/2 transport failure", details);
    }

    public Http2TransportException(String details, Throwable cause) {
        super(ErrorCodes.HTTP2_TRANSPORT_FAILURE, "HTTP/2 transport failure", details, cause);
    }
}
