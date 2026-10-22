package com.paicore.sba.stack.api.error;

/**
 * Inbound-security failure categories used in {@link ProblemDetailsResponse#cause()}.
 * Each entry carries the HTTP status (RFC 6750 §3) and {@code WWW-Authenticate}
 * challenge (RFC 7235 §3.1) the caller should emit.
 */
public enum SecurityFailureCause {

    MISSING_AUTHORIZATION_HEADER(401, Challenges.BEARER_REALM_TEMPLATE),
    INVALID_TOKEN(401, Challenges.BEARER_INVALID_TOKEN),
    UNKNOWN_ISSUER(401, Challenges.BEARER_INVALID_TOKEN),
    UNKNOWN_GATEWAY(401, Challenges.BEARER_INVALID_TOKEN),
    INVALID_SIGNATURE(401, Challenges.BEARER_INVALID_TOKEN),
    TOKEN_EXPIRED(401, Challenges.BEARER_INVALID_TOKEN),
    ISSUER_MISMATCH(401, Challenges.BEARER_INVALID_TOKEN),
    DISALLOWED_ALGORITHM(401, Challenges.BEARER_INVALID_TOKEN),
    INVALID_SCOPE(403, "Bearer error=\"insufficient_scope\""),
    JWKS_UNAVAILABLE(503, null);

    public static final String BEARER_INVALID_TOKEN = Challenges.BEARER_INVALID_TOKEN;

    private final int httpStatus;
    private final String wwwAuthenticate;

    private static final class Challenges {
        static final String BEARER_INVALID_TOKEN = "Bearer error=\"invalid_token\"";
        static final String BEARER_REALM_TEMPLATE = "Bearer realm=\"%s\"";

        private Challenges() {
        }
    }

    SecurityFailureCause(int httpStatus, String wwwAuthenticate) {
        this.httpStatus = httpStatus;
        this.wwwAuthenticate = wwwAuthenticate;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String wwwAuthenticate() {
        return wwwAuthenticate;
    }

    /** Only {@link #MISSING_AUTHORIZATION_HEADER} substitutes the realm; others ignore it. */
    public String wwwAuthenticate(String realm) {
        if (this == MISSING_AUTHORIZATION_HEADER) {
            return String.format(Challenges.BEARER_REALM_TEMPLATE, realm == null ? "" : realm);
        }
        return wwwAuthenticate;
    }

    public String problemTypeUri(String base) {
        String prefix;
        if (base == null) {
            prefix = "";
        } else {
            prefix = base.endsWith("/") ? base : base + "/";
        }
        return prefix + name().toLowerCase().replace('_', '-');
    }
}
