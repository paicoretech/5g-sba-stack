package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.Generated;

@Generated
public class SmsDeliveryFailedException extends SdkException {

    public SmsDeliveryFailedException(String details) {
        super(ErrorCodes.MT_DELIVERY_FAILED, "MT message delivery failed", details);
    }

    public SmsDeliveryFailedException(String details, Throwable cause) {
        super(ErrorCodes.MT_DELIVERY_FAILED, "MT message delivery failed", details, cause);
    }
}
