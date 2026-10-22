package com.paicore.sba.stack.transport.http2.util;

public final class Http2Constants {

    public static final String PSEUDO_METHOD = ":method";
    public static final String PSEUDO_SCHEME = ":scheme";
    public static final String PSEUDO_PATH = ":path";
    public static final String PSEUDO_AUTHORITY = ":authority";
    public static final String PSEUDO_STATUS = ":status";

    public static final String HEADER_CONTENT_TYPE = "content-type";
    public static final String HEADER_CONTENT_LENGTH = "content-length";
    public static final String HEADER_USER_AGENT = "user-agent";

    public static final String CONTENT_TYPE_3GPP_JSON = "application/json";
    public static final String CONTENT_TYPE_3GPP_PROBLEM_JSON = "application/problem+json";
    public static final String CONTENT_TYPE_3GPP_SMS = "application/vnd.3gpp.sms";
    public static final String CONTENT_TYPE_MULTIPART_RELATED = "multipart/related";

    public static final String SCHEME_HTTPS = "https";
    public static final String DEFAULT_USER_AGENT = "sba-stack-http2/1.0.0-SNAPSHOT";

    public static final int DEFAULT_MAX_CONCURRENT_STREAMS = 10_000;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private Http2Constants() {
        throw new IllegalStateException("Constants class");
    }
}
