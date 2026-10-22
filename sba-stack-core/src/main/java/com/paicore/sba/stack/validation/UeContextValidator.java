package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.utils.ErrorCodes;

public final class UeContextValidator {

    private UeContextValidator() {
        throw new IllegalStateException("Utility class");
    }

    @SuppressWarnings("java:S2589")
    public static void requireValid(UeSmsContextData ctx) {
        if (ctx == null) {
            throw new SdkException(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "Invalid registration payload", "UeSmsContextData is null");
        }
        SupiValidator.requireValid(ctx.getSupi());
        if (ctx.getGpsi() != null) {
            GpsiValidator.requireValid(ctx.getGpsi());
        }
        if (ctx.getPei() != null) {
            PeiValidator.requireValid(ctx.getPei());
        }
        if (ctx.getAccessType() == null) {
            throw new SdkException(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "Invalid registration payload", "accessType is required");
        }
    }
}
