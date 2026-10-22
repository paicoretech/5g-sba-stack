package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidPeiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeiValidatorTest {

    @Test
    void acceptsImeiWithValidLuhn() {
        // 490154203237518 is a canonical Luhn-valid IMEI sample.
        assertTrue(PeiValidator.isValid("imei-490154203237518"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "imei-490154203237510",
            "imei-12345678901234",
            "imei-1234567890123456",
            "imei-abcdefghijklmno",
            "IMEI-490154203237518",
            "imeisv-4901542032375180"
    })
    void rejectsMalformedOrInvalidLuhn(String pei) {
        assertFalse(PeiValidator.isValid(pei));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsNullOrBlank(String pei) {
        assertFalse(PeiValidator.isValid(pei));
    }

    @Test
    void requireValidThrowsOnInvalidLuhn() {
        assertThrows(InvalidPeiException.class,
                () -> PeiValidator.requireValid("imei-490154203237510"));
    }

    @Test
    void requireValidThrowsOnMissingPrefix() {
        assertThrows(InvalidPeiException.class,
                () -> PeiValidator.requireValid("490154203237518"));
    }
}
