package com.paicore.sba.stack.dto;

import com.paicore.sba.stack.exceptions.InvalidSupiException;
import com.paicore.sba.stack.utils.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedErrorTest {

    @Test
    void ofPopulatesMessageFromCatalog() {
        NormalizedError err = NormalizedError.of(ErrorCodes.INVALID_SUPI, "imsi-abc");
        assertEquals(ErrorCodes.INVALID_SUPI, err.getCode());
        assertEquals("Invalid SUPI", err.getMessage());
        assertEquals("imsi-abc", err.getDetails());
    }

    @Test
    void fromSdkExceptionCopiesFields() {
        InvalidSupiException ex = new InvalidSupiException("imsi-abc");
        NormalizedError err = NormalizedError.from(ex);
        assertEquals(ErrorCodes.INVALID_SUPI, err.getCode());
        assertEquals("Invalid SUPI", err.getMessage());
        assertEquals("imsi-abc", err.getDetails());
    }
}
