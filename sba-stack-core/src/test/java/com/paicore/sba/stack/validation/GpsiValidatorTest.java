package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidGpsiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsiValidatorTest {

    @Test
    void acceptsCanonicalMsisdnGpsi() {
        assertTrue(GpsiValidator.isValid("msisdn-8613915900000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "msisdn-1",
            "msisdn-15555550100",
            "msisdn-123456789012345"
    })
    void acceptsBoundaryDigitLengths(String gpsi) {
        assertTrue(GpsiValidator.isValid(gpsi));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "msisdn-1234567890123456",
            "msisdn-abc",
            "MSISDN-8613915900000",
            "imsi-460000000000001",
            "8613915900000",
            "msisdn-"
    })
    void rejectsMalformedGpsi(String gpsi) {
        assertFalse(GpsiValidator.isValid(gpsi));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsNullOrBlank(String gpsi) {
        assertFalse(GpsiValidator.isValid(gpsi));
    }

    @Test
    void requireValidThrowsOnMalformed() {
        assertThrows(InvalidGpsiException.class,
                () -> GpsiValidator.requireValid("msisdn-abc"));
    }
}
