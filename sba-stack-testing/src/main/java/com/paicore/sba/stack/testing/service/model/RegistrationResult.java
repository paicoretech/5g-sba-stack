package com.paicore.sba.stack.testing.service.model;

/**
 * Outcome of a registration attempt as returned by
 * {@link com.paicore.sba.stack.testing.service.RegistrationService}.
 */
public record RegistrationResult(
        boolean accepted,
        UeSession session,
        String errorCode,
        String errorDetail) {

    public static RegistrationResult accepted(UeSession session) {
        return new RegistrationResult(true, session, null, null);
    }

    public static RegistrationResult rejected(String errorCode, String detail) {
        return new RegistrationResult(false, null, errorCode, detail);
    }
}
