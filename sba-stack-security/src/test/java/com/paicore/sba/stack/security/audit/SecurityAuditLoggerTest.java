package com.paicore.sba.stack.security.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.auth.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SecurityAuditLoggerTest {

    private static final String PATH = "/nsmsf-sms/v2/ue-contexts/test";

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;
    private SecurityAuditMetrics metrics;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(SecurityAuditLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);
        metrics = mock(SecurityAuditMetrics.class);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("logSuccess writes an INFO line when logSuccess flag is true")
    void logSuccess_ShouldEmitInfoLineWhenEnabled() {
        SecurityAuditLogger audit = new SecurityAuditLogger(true, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();
        Principal principal = Principal.builder()
                .id("urn:uuid:amf-001")
                .issuer("https://issuer.local")
                .scopes(Set.of("nsmsf-sms"))
                .build();

        audit.logSuccess(request, 42, principal);

        assertThat(appender.list).hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("auth.success")
                            .contains("path=" + PATH)
                            .contains("gateway.networkId=42")
                            .contains("sub=urn:uuid:amf-001")
                            .contains("scope=nsmsf-sms");
                });
    }

    @Test
    @DisplayName("logSuccess never writes a log line when logSuccess flag is false but still increments metrics")
    void logSuccess_ShouldSkipLoggingButIncrementMetricsWhenDisabled() {
        SecurityAuditLogger audit = new SecurityAuditLogger(false, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();
        Principal principal = Principal.builder().id("amf").issuer("iss").build();

        audit.logSuccess(request, 1, principal);

        assertThat(appender.list).isEmpty();
        verify(metrics).incrementAuthSuccess();
    }

    @Test
    @DisplayName("logFailure always emits a WARN line and increments the failure metric")
    void logFailure_ShouldEmitWarnLineAndIncrementFailureMetric() {
        SecurityAuditLogger audit = new SecurityAuditLogger(false, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();

        audit.logFailure(request, SecurityFailureCause.INVALID_TOKEN, "abcdef1234567890extra",
                "JWT signature mismatch");

        assertThat(appender.list).hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("auth.failure")
                            .contains("cause=INVALID_TOKEN")
                            .contains("path=" + PATH)
                            .contains("JWT signature mismatch");
                });
        verify(metrics).incrementAuthFailure();
    }

    @Test
    @DisplayName("logFailure masks the token, leaving only the first 8 chars followed by an ellipsis")
    void logFailure_ShouldMaskTokenToFirstEightChars() {
        SecurityAuditLogger audit = new SecurityAuditLogger(false, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();

        audit.logFailure(request, SecurityFailureCause.INVALID_TOKEN,
                "supersecrettoken-1234567890abcdef", "bad signature");

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("jwt_prefix=supersec...")
                .doesNotContain("1234567890abcdef");
    }

    @Test
    @DisplayName("logFailure renders the absent marker when the token is null or blank")
    void logFailure_ShouldRenderAbsentMarkerForNullOrBlankToken() {
        SecurityAuditLogger audit = new SecurityAuditLogger(false, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();

        audit.logFailure(request, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, null, "no header");
        audit.logFailure(request, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, "   ", "blank header");

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("jwt_prefix=<absent>");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("jwt_prefix=<absent>");
    }

    @Test
    @DisplayName("logFailure appends an ellipsis to short tokens (length less than or equal to 8)")
    void logFailure_ShouldStillAppendEllipsisForShortTokens() {
        SecurityAuditLogger audit = new SecurityAuditLogger(false, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();

        audit.logFailure(request, SecurityFailureCause.INVALID_TOKEN, "abc", "short token");

        assertThat(appender.list.get(0).getFormattedMessage()).contains("jwt_prefix=abc...");
    }

    @Test
    @DisplayName("logSuccess truncates an issuer longer than 30 chars with an ellipsis")
    void logSuccess_ShouldTruncateLongIssuerToThirtyChars() {
        SecurityAuditLogger audit = new SecurityAuditLogger(true, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();
        String longIssuer = "https://issuer.example.com/very/long/path/segment";
        Principal principal = Principal.builder()
                .id("amf")
                .issuer(longIssuer)
                .build();

        audit.logSuccess(request, 7, principal);

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("iss=" + longIssuer.substring(0, 30) + "...")
                .doesNotContain("very/long/path/segment");
    }

    @Test
    @DisplayName("logSuccess renders absent markers when principal fields are null or empty")
    void logSuccess_ShouldRenderAbsentMarkerForNullPrincipalFields() {
        SecurityAuditLogger audit = new SecurityAuditLogger(true, metrics);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();
        Principal anonymous = Principal.builder().build();

        audit.logSuccess(request, 9, anonymous);

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("iss=<absent>")
                .contains("sub=<absent>")
                .contains("scope=<absent>");
    }

    @Test
    @DisplayName("null metrics passed into the constructor is replaced by the NOOP default")
    void constructor_ShouldFallBackToNoopMetricsWhenNullProvided() {
        SecurityAuditLogger audit = new SecurityAuditLogger(true, null);
        ServerHttpRequest request = MockServerHttpRequest.get(PATH).build();
        Principal principal = Principal.builder().id("amf").build();

        audit.logSuccess(request, 1, principal);
        audit.logFailure(request, SecurityFailureCause.INVALID_TOKEN, "tok", "bad");

        verifyNoInteractions(metrics);
        assertThat(appender.list).hasSize(2);
    }
}
