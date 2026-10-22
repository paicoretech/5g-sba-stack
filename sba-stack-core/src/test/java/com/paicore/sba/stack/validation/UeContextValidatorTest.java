package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidGpsiException;
import com.paicore.sba.stack.exceptions.InvalidSupiException;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.openapi.model.AccessType;
import com.paicore.sba.stack.openapi.model.RatType;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.utils.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UeContextValidatorTest {

    @Test
    void acceptsFullyPopulatedContext() {
        UeSmsContextData ctx = new UeSmsContextData()
                .supi("imsi-460000000000001")
                .gpsi("msisdn-8613915900000")
                .pei("imei-490154203237518")
                .accessType(AccessType._3_GPP_ACCESS)
                .amfId(UUID.fromString("54804518-e30d-11e8-9f32-f2801f1b9fd1"))
                .ratType(RatType.NR);

        assertDoesNotThrow(() -> UeContextValidator.requireValid(ctx));
    }

    @Test
    void rejectsNullContext() {
        SdkException ex = assertThrows(SdkException.class,
                () -> UeContextValidator.requireValid(null));
        assertEquals(ErrorCodes.INVALID_REGISTRATION_PAYLOAD, ex.getCode());
    }

    @Test
    void rejectsContextWithoutAccessType() {
        UeSmsContextData ctx = new UeSmsContextData()
                .supi("imsi-460000000000001");

        SdkException ex = assertThrows(SdkException.class,
                () -> UeContextValidator.requireValid(ctx));
        assertEquals(ErrorCodes.INVALID_REGISTRATION_PAYLOAD, ex.getCode());
    }

    @Test
    void propagatesSupiValidationError() {
        UeSmsContextData ctx = new UeSmsContextData()
                .supi("imsi-abc")
                .accessType(AccessType._3_GPP_ACCESS);

        assertThrows(InvalidSupiException.class, () -> UeContextValidator.requireValid(ctx));
    }

    @Test
    void propagatesGpsiValidationError() {
        UeSmsContextData ctx = new UeSmsContextData()
                .supi("imsi-460000000000001")
                .gpsi("msisdn-abc")
                .accessType(AccessType._3_GPP_ACCESS);

        assertThrows(InvalidGpsiException.class, () -> UeContextValidator.requireValid(ctx));
    }
}
