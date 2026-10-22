package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidSupiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupiValidatorTest {

    @Test
    void acceptsCanonicalImsiSupi() {
        assertTrue(SupiValidator.isValid("imsi-460000000000001"));
        assertDoesNotThrow(() -> SupiValidator.requireValid("imsi-460000000000001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "imsi-460001",
            "imsi-460000000000001",
            "imsi-123456789012345"
    })
    void acceptsBoundaryDigitLengths(String supi) {
        assertTrue(SupiValidator.isValid(supi));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "imsi-12345",
            "imsi-1234567890123456",
            "imsi-abc123",
            "IMSI-460000000000001",
            "nai-user@realm",
            "msisdn-8613915900000",
            "460000000000001"
    })
    void rejectsMalformedSupi(String supi) {
        assertFalse(SupiValidator.isValid(supi));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsNullOrBlank(String supi) {
        assertFalse(SupiValidator.isValid(supi));
    }

    @Test
    void requireValidThrowsOnMalformed() {
        InvalidSupiException ex = assertThrows(InvalidSupiException.class,
                () -> SupiValidator.requireValid("imsi-abc"));
        assertTrue(ex.getDetails().contains("imsi-abc"));
    }
}
