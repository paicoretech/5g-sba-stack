package com.paicore.sba.stack.utils;

public final class SdkConstants {

    public static final String SDK_NAME = "sba-stack";
    public static final String SDK_VERSION = "1.0.0-SNAPSHOT";

    public static final String SUPI_PREFIX_IMSI = "imsi-";
    public static final String GPSI_PREFIX_MSISDN = "msisdn-";
    public static final String PEI_PREFIX_IMEI = "imei-";

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_PROBLEM_JSON = "application/problem+json";

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_MESSAGE_ID = "X-Message-Id";

    public static final String ERROR_CODE_PREFIX = "SMSF-";

    private SdkConstants() {
        throw new IllegalStateException("Constants class");
    }
}
