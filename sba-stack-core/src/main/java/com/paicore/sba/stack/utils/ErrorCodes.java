package com.paicore.sba.stack.utils;

import java.util.Map;

import static java.util.Map.entry;

public final class ErrorCodes {

    public static final String INVALID_SUPI = "SMSF-4001";
    public static final String INVALID_GPSI = "SMSF-4002";
    public static final String INVALID_PEI = "SMSF-4003";
    public static final String INVALID_REGISTRATION_PAYLOAD = "SMSF-4004";
    public static final String INVALID_MO_PAYLOAD = "SMSF-4005";
    public static final String INVALID_MT_PAYLOAD = "SMSF-4006";

    public static final String SESSION_NOT_FOUND = "SMSF-4040";
    public static final String UE_NOT_REGISTERED = "SMSF-4041";

    public static final String HTTP2_TRANSPORT_FAILURE = "SMSF-5001";
    public static final String MT_DELIVERY_FAILED = "SMSF-5002";
    public static final String DLR_PROCESSING_FAILED = "SMSF-5003";

    public static final String INTERNAL_SERIALIZATION = "SMSF-5900";
    public static final String INTERNAL_UNEXPECTED = "SMSF-5999";

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            entry(INVALID_SUPI, "Invalid SUPI"),
            entry(INVALID_GPSI, "Invalid GPSI"),
            entry(INVALID_PEI, "Invalid PEI"),
            entry(INVALID_REGISTRATION_PAYLOAD, "Invalid registration payload"),
            entry(INVALID_MO_PAYLOAD, "Invalid MO payload"),
            entry(INVALID_MT_PAYLOAD, "Invalid MT payload"),
            entry(SESSION_NOT_FOUND, "Session not found"),
            entry(UE_NOT_REGISTERED, "UE is not registered"),
            entry(HTTP2_TRANSPORT_FAILURE, "HTTP/2 transport failure"),
            entry(MT_DELIVERY_FAILED, "MT message delivery failed"),
            entry(DLR_PROCESSING_FAILED, "DLR processing failed"),
            entry(INTERNAL_SERIALIZATION, "Serialization/deserialization failure"),
            entry(INTERNAL_UNEXPECTED, "Unexpected internal error")
    );

    private ErrorCodes() {
        throw new IllegalStateException("Constants class");
    }

    public static String describe(String code) {
        return DESCRIPTIONS.getOrDefault(code, "Unknown error");
    }
}
