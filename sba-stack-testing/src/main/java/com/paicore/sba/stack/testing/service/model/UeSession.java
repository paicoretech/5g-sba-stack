package com.paicore.sba.stack.testing.service.model;

import com.paicore.sba.stack.openapi.model.UeSmsContextData;

import java.time.Instant;

/**
 * In-memory representation of a registered UE — what {@link
 * com.paicore.sba.stack.testing.service.RegistrationService} keeps per SUPI.
 */
public record UeSession(
        String supi,
        UeSmsContextData context,
        String sessionId,
        Instant registeredAt) {
}
