package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * The mock UDM keeps one of these per registered SUPI so tests can assert
 * who registered, when, and which AMF context they brought with them.
 */
@Getter
@Builder
public class RegisteredUe {

    private final String supi;
    private final UeSmsContextData context;
    private final Instant registeredAt;
    private final String sessionId;
}
