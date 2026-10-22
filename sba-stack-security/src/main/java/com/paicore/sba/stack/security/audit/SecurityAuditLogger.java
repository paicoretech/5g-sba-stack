package com.paicore.sba.stack.security.audit;

import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.auth.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Objects;
import java.util.Set;

@Slf4j
public class SecurityAuditLogger {

    private static final int TOKEN_PREVIEW_LENGTH = 8;
    private static final int ISSUER_PREVIEW_LENGTH = 30;
    private static final String ABSENT_MARKER = "<absent>";

    private final boolean logSuccess;
    private final SecurityAuditMetrics metrics;

    public SecurityAuditLogger(boolean logSuccess, SecurityAuditMetrics metrics) {
        this.logSuccess = logSuccess;
        this.metrics = Objects.requireNonNullElse(metrics, SecurityAuditMetrics.NOOP);
    }

    public void logSuccess(ServerHttpRequest request, int networkId, Principal principal) {
        metrics.incrementAuthSuccess();
        if (!logSuccess) {
            return;
        }
        log.info("auth.success path={} gateway.networkId={} iss={} sub={} scope={}",
                request.getPath().value(),
                networkId,
                preview(principal.getIssuer()),
                Objects.requireNonNullElse(principal.getId(), ABSENT_MARKER),
                safeScope(principal.getScopes()));
    }

    public void logFailure(ServerHttpRequest request, SecurityFailureCause cause, String token, String detail) {
        metrics.incrementAuthFailure();
        log.warn("auth.failure cause={} path={} jwt_prefix={} detail={}",
                cause.name(),
                request.getPath().value(),
                maskToken(token),
                detail);
    }

    private static String maskToken(String token) {
        if (Objects.isNull(token) || token.isBlank()) {
            return ABSENT_MARKER;
        }
        if (token.length() <= TOKEN_PREVIEW_LENGTH) {
            return token + "...";
        }
        return token.substring(0, TOKEN_PREVIEW_LENGTH) + "...";
    }

    private static String preview(String value) {
        if (Objects.isNull(value)) {
            return ABSENT_MARKER;
        }
        if (value.length() <= ISSUER_PREVIEW_LENGTH) {
            return value;
        }
        return value.substring(0, ISSUER_PREVIEW_LENGTH) + "...";
    }

    private static String safeScope(Set<String> scopes) {
        if (Objects.isNull(scopes) || scopes.isEmpty()) {
            return ABSENT_MARKER;
        }
        return String.join(" ", scopes);
    }
}
