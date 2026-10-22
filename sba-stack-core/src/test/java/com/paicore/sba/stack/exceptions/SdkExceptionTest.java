package com.paicore.sba.stack.exceptions;

import com.paicore.sba.stack.utils.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SdkExceptionTest {

    @Test
    void carriesCodeAndDetails() {
        SdkException ex = new SdkException(ErrorCodes.INVALID_SUPI, "Invalid SUPI", "imsi-abc");
        assertEquals(ErrorCodes.INVALID_SUPI, ex.getCode());
        assertEquals("Invalid SUPI", ex.getMessage());
        assertEquals("imsi-abc", ex.getDetails());
        assertEquals("Invalid SUPI", ex.describe());
    }

    @Test
    void preservesCauseWhenProvided() {
        IllegalArgumentException cause = new IllegalArgumentException("boom");
        SdkException ex = new SdkException(ErrorCodes.INTERNAL_UNEXPECTED, "wrap", cause);
        assertSame(cause, ex.getCause());
        assertEquals("boom", ex.getDetails());
    }

    @Test
    void detailsNullableWithSimpleConstructor() {
        SdkException ex = new SdkException(ErrorCodes.SESSION_NOT_FOUND, "missing");
        assertNotNull(ex.getCode());
        assertNull(ex.getDetails());
    }

    @Test
    void invalidSupiExceptionBindsExpectedCode() {
        InvalidSupiException ex = new InvalidSupiException("imsi-abc");
        assertEquals(ErrorCodes.INVALID_SUPI, ex.getCode());
        assertEquals("imsi-abc", ex.getDetails());
    }

    @Test
    void smsDeliveryFailedExceptionBindsExpectedCode() {
        SmsDeliveryFailedException ex = new SmsDeliveryFailedException("timeout");
        assertEquals(ErrorCodes.MT_DELIVERY_FAILED, ex.getCode());
    }
}
