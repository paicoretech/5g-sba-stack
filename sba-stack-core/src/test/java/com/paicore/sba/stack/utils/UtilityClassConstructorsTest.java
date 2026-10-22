package com.paicore.sba.stack.utils;

import com.paicore.sba.stack.mo.MultipartRelatedDecoder;
import com.paicore.sba.stack.validation.E164Validator;
import com.paicore.sba.stack.validation.FqdnValidator;
import com.paicore.sba.stack.validation.GpsiValidator;
import com.paicore.sba.stack.validation.PeiValidator;
import com.paicore.sba.stack.validation.SupiValidator;
import com.paicore.sba.stack.validation.UeContextValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Utility classes ship a private constructor that throws {@link IllegalStateException}.
 * The instances are never created in production code, so JaCoCo still counts the
 * constructor as missed. We invoke them reflectively here to assert the contract.
 */
class UtilityClassConstructorsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {
            ErrorCodes.class,
            SdkConstants.class,
            SdkConverter.class,
            MultipartRelatedDecoder.class,
            GpsiValidator.class,
            SupiValidator.class,
            PeiValidator.class,
            UeContextValidator.class,
            E164Validator.class,
            FqdnValidator.class
    })
    @DisplayName("private constructor throws IllegalStateException for every SDK utility class")
    void privateConstructorShouldThrowIllegalStateException(Class<?> utilityClass) throws Exception {
        Constructor<?> ctor = utilityClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        InvocationTargetException ite = assertThrows(InvocationTargetException.class, ctor::newInstance);
        assertInstanceOf(IllegalStateException.class, ite.getCause());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("ErrorCodes.describe falls back to 'Unknown error' for codes not in the catalogue")
    void describeShouldFallBackForUnknownCodes() {
        assertEquals("Unknown error", ErrorCodes.describe("SMSF-9999"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("ErrorCodes.describe returns the canonical message for every published code")
    void describeShouldReturnCanonicalMessageForKnownCodes() {
        assertEquals("Invalid SUPI", ErrorCodes.describe(ErrorCodes.INVALID_SUPI));
        assertEquals("HTTP/2 transport failure", ErrorCodes.describe(ErrorCodes.HTTP2_TRANSPORT_FAILURE));
        assertEquals("MT message delivery failed", ErrorCodes.describe(ErrorCodes.MT_DELIVERY_FAILED));
        assertEquals("UE is not registered", ErrorCodes.describe(ErrorCodes.UE_NOT_REGISTERED));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("SdkConstants exposes the canonical prefixes and content-types")
    void sdkConstantsExposesCanonicalValues() {
        assertEquals("imsi-", SdkConstants.SUPI_PREFIX_IMSI);
        assertEquals("msisdn-", SdkConstants.GPSI_PREFIX_MSISDN);
        assertEquals("imei-", SdkConstants.PEI_PREFIX_IMEI);
        assertEquals("application/json", SdkConstants.CONTENT_TYPE_JSON);
        assertTrue(SdkConstants.ERROR_CODE_PREFIX.startsWith("SMSF"));
    }
}
