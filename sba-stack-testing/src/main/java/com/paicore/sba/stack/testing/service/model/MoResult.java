package com.paicore.sba.stack.testing.service.model;

/**
 * Outcome of MO sendsms processing as returned by
 * {@link com.paicore.sba.stack.testing.service.MoService}. Carries the
 * fields the SBA response needs ({@code smsRecordId}, {@code tpduType},
 * {@code decodedMessage}) plus the normalised error code on rejection.
 */
public record MoResult(
        boolean accepted,
        String smsRecordId,
        String tpduType,
        String decodedMessage,
        String errorCode,
        String errorDetail) {

    public static MoResult accepted(String smsRecordId, String tpduType, String decodedMessage) {
        return new MoResult(true, smsRecordId, tpduType, decodedMessage, null, null);
    }

    public static MoResult rejected(String errorCode, String detail) {
        return new MoResult(false, null, null, null, errorCode, detail);
    }
}
