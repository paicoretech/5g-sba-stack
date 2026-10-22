package com.paicore.sba.stack.security.webfilter;

import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.audit.SecurityAuditLogger;
import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.auth.Principal;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.security.problem.SecurityProblemWriter;
import com.paicore.sba.stack.security.tls.PeerCertValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Http2SecurityWebFilterTest {

    private static final String BASE_PATH = "/nsmsf-sms/v2";
    private static final int NETWORK_ID = 42;
    private static final String SECURED_PATH = BASE_PATH + "/" + NETWORK_ID + "/ue-contexts/abc";
    private static final String CALLBACK_PATH = BASE_PATH + "/" + NETWORK_ID + "/namf-cb/n1n2-failure/1783981200123-86234715900123";

    private GatewaySecurityLookup lookup;
    private SecurityProblemWriter problemWriter;
    private SecurityAuditLogger auditLogger;
    private PeerCertValidator peerCertValidator;
    private GatewaySecurityContext gateway;
    private Authenticator authenticator;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        lookup = mock(GatewaySecurityLookup.class);
        problemWriter = mock(SecurityProblemWriter.class);
        auditLogger = mock(SecurityAuditLogger.class);
        peerCertValidator = mock(PeerCertValidator.class);
        gateway = mock(GatewaySecurityContext.class);
        authenticator = mock(Authenticator.class);
        chain = mock(WebFilterChain.class);

        when(problemWriter.write(any(), any(), any())).thenReturn(Mono.empty());
        when(chain.filter(any())).thenReturn(Mono.empty());
        when(peerCertValidator.extract(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("securityEnabled=false short-circuits and forwards the request to the chain")
    void filter_GivenSecurityDisabled_ShouldForwardToChain() {
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().securityEnabled(false).build());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(problemWriter, never()).write(any(), any(), any());
    }

    @Test
    @DisplayName("path matching bypassPaths forwards directly without any gateway lookup")
    void filter_GivenBypassPathMatch_ShouldForwardToChain() {
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder()
                .bypassPaths(List.of("/actuator/**"))
                .build());
        MockServerWebExchange exchange = newGetExchange("/actuator/health");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("the AMF callback route is secured like any other SBI path: no bearer, no entry")
    void filter_GivenCallbackPathWithoutBearer_ShouldReject() {
        gatewayBundle(false, true, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(CALLBACK_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, "Authorization header is missing");
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("the AMF callback route resolves its gateway from the networkId in the path")
    void filter_GivenCallbackPath_ShouldResolveTheGateway() {
        gatewayBundle(false, false, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(CALLBACK_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(lookup).findByNetworkId(NETWORK_ID);
        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("path that cannot yield a valid networkId is rejected with UNKNOWN_GATEWAY")
    void filter_GivenPathWithoutNetworkId_ShouldRejectWithUnknownGateway() {
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange("/nsmsf-sms/v2/not-a-number/abc");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.UNKNOWN_GATEWAY, "valid networkId");
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("missing gateway for networkId is rejected with UNKNOWN_GATEWAY")
    void filter_GivenUnknownGateway_ShouldRejectWithUnknownGateway() {
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        when(lookup.findByNetworkId(NETWORK_ID)).thenReturn(Optional.empty());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.UNKNOWN_GATEWAY, "No gateway registered");
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("require_mtls=true with no client certificate is rejected with INVALID_TOKEN")
    void filter_GivenMtlsRequiredAndNoClientCert_ShouldRejectWithInvalidToken() {
        gatewayBundle(true, false, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.INVALID_TOKEN, "no client certificate");
    }

    @Test
    @DisplayName("require_mtls=true with client cert that does not match the pinned material is rejected")
    void filter_GivenMtlsRequiredAndPinningMismatch_ShouldRejectWithInvalidToken() {
        gatewayBundle(true, false, "/etc/pinned-amf.crt");
        X509Certificate peer = mock(X509Certificate.class);
        when(peerCertValidator.extract(any())).thenReturn(Optional.of(peer));
        when(peerCertValidator.matchesPinned(peer, "/etc/pinned-amf.crt")).thenReturn(false);

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.INVALID_TOKEN, "does not match expected AMF certificate");
    }

    @Test
    @DisplayName("oauth2_enabled=false skips token validation and forwards an anonymous Principal")
    void filter_GivenOauth2Disabled_ShouldForwardAnonymousPrincipal() {
        gatewayBundle(false, false, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        Principal stored = (Principal) exchange.getAttributes()
                .get(Http2SecurityWebFilter.EXCHANGE_ATTR_PRINCIPAL);
        assertThat(stored).isNotNull();
        assertThat(stored.getId()).isNull();
        verify(auditLogger).logSuccess(any(), eq(NETWORK_ID), any(Principal.class));
    }

    @Test
    @DisplayName("missing Authorization header is rejected with MISSING_AUTHORIZATION_HEADER")
    void filter_GivenNoBearerHeader_ShouldRejectWithMissingAuthorization() {
        gatewayBundle(false, true, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = newGetExchange(SECURED_PATH);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, "Authorization header is missing");
    }

    @Test
    @DisplayName("Authorization header without the Bearer prefix is rejected with MISSING_AUTHORIZATION_HEADER")
    void filter_GivenMalformedBearerHeader_ShouldRejectWithMissingAuthorization() {
        gatewayBundle(false, true, null);
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(SECURED_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Basic abc123")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, "Authorization header is missing");
    }

    @Test
    @DisplayName("Authenticator AuthenticationException propagates with its declared failure cause")
    void filter_GivenAuthenticatorRaisesScopeError_ShouldRejectWithInvalidScope() {
        gatewayBundle(false, true, null);
        when(lookup.authenticatorForNetworkId(NETWORK_ID)).thenReturn(Optional.of(authenticator));
        when(authenticator.authenticate("good-token"))
                .thenThrow(new AuthenticationException("missing nsmsf-sms scope",
                        SecurityFailureCause.INVALID_SCOPE));

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = bearer(SECURED_PATH, "good-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.INVALID_SCOPE, "missing nsmsf-sms scope");
    }

    @Test
    @DisplayName("Authenticator AuthenticationException without failure cause falls back to INVALID_TOKEN")
    void filter_GivenAuthenticatorRaisesUncategorisedError_ShouldFallBackToInvalidToken() {
        gatewayBundle(false, true, null);
        when(lookup.authenticatorForNetworkId(NETWORK_ID)).thenReturn(Optional.of(authenticator));
        when(authenticator.authenticate("good-token"))
                .thenThrow(new AuthenticationException("opaque failure"));

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = bearer(SECURED_PATH, "good-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.INVALID_TOKEN, "opaque failure");
    }

    @Test
    @DisplayName("no authenticator configured for the gateway yields a JWKS_UNAVAILABLE rejection")
    void filter_GivenNoAuthenticatorRegistered_ShouldRejectWithJwksUnavailable() {
        gatewayBundle(false, true, null);
        when(lookup.authenticatorForNetworkId(NETWORK_ID)).thenReturn(Optional.empty());

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = bearer(SECURED_PATH, "tok");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.JWKS_UNAVAILABLE, "No JWKS endpoint configured");
    }

    @Test
    @DisplayName("mTLS + JWT where JWT.sub does not match SAN URI is rejected with INVALID_TOKEN and TS 33.501 detail")
    void filter_GivenMtlsAndSubSanMismatch_ShouldRejectWithInvalidToken() {
        gatewayBundle(true, true, "/etc/pinned.crt");
        X509Certificate peer = mock(X509Certificate.class);
        when(peerCertValidator.extract(any())).thenReturn(Optional.of(peer));
        when(peerCertValidator.matchesPinned(peer, "/etc/pinned.crt")).thenReturn(true);
        when(peerCertValidator.sanContainsPrincipalId(peer, "urn:uuid:amf-001")).thenReturn(false);
        when(lookup.authenticatorForNetworkId(NETWORK_ID)).thenReturn(Optional.of(authenticator));
        when(authenticator.authenticate("tok"))
                .thenReturn(Principal.builder().id("urn:uuid:amf-001").issuer("iss").build());

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = bearer(SECURED_PATH, "tok");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyProblem(exchange, SecurityFailureCause.INVALID_TOKEN, "TS 33.501");
    }

    @Test
    @DisplayName("happy path: mTLS + valid JWT + sub matches SAN URI invokes chain and stores attributes")
    void filter_GivenValidMtlsAndJwt_ShouldForwardAndLogSuccess() {
        gatewayBundle(true, true, "/etc/pinned.crt");
        X509Certificate peer = mock(X509Certificate.class);
        when(peerCertValidator.extract(any())).thenReturn(Optional.of(peer));
        when(peerCertValidator.matchesPinned(peer, "/etc/pinned.crt")).thenReturn(true);
        when(peerCertValidator.sanContainsPrincipalId(peer, "urn:uuid:amf-001")).thenReturn(true);
        when(lookup.authenticatorForNetworkId(NETWORK_ID)).thenReturn(Optional.of(authenticator));
        Principal principal = Principal.builder().id("urn:uuid:amf-001").issuer("https://amf").build();
        when(authenticator.authenticate("tok")).thenReturn(principal);

        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        MockServerWebExchange exchange = bearer(SECURED_PATH, "tok");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(auditLogger).logSuccess(any(), eq(NETWORK_ID), eq(principal));
        assertThat(exchange.getAttributes().get(Http2SecurityWebFilter.EXCHANGE_ATTR_GATEWAY))
                .isSameAs(gateway);
        assertThat(exchange.getAttributes().get(Http2SecurityWebFilter.EXCHANGE_ATTR_PRINCIPAL))
                .isSameAs(principal);
    }

    @Test
    @DisplayName("getOrder returns a value near the highest precedence to run before other filters")
    void getOrder_ShouldReturnHighestPrecedenceWithSmallOffset() {
        Http2SecurityWebFilter filter = newFilter(filterConfigBuilder().build());
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 10);
    }

    private Http2SecurityWebFilter newFilter(Http2SecurityFilterConfig config) {
        return new Http2SecurityWebFilter(config, lookup, problemWriter, auditLogger, peerCertValidator);
    }

    private Http2SecurityFilterConfig.Http2SecurityFilterConfigBuilder filterConfigBuilder() {
        return Http2SecurityFilterConfig.builder()
                .securityEnabled(true)
                .sbiServerBasePath(BASE_PATH)
                .bypassPaths(List.of())
                .bearerHeaderName(HttpHeaders.AUTHORIZATION)
                .bearerScheme("Bearer");
    }

    private void gatewayBundle(boolean mtls, boolean oauth2, String pinnedCertPath) {
        when(gateway.networkId()).thenReturn(NETWORK_ID);
        when(gateway.isRequireMtls()).thenReturn(mtls);
        when(gateway.isOauth2Enabled()).thenReturn(oauth2);
        when(gateway.expectedPeerCertificatePath()).thenReturn(pinnedCertPath);
        Optional<GatewaySecurityContext> bundle = Optional.of(gateway);
        when(lookup.findByNetworkId(NETWORK_ID)).thenAnswer(inv -> bundle);
    }

    private static MockServerWebExchange newGetExchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private static MockServerWebExchange bearer(String path, String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());
    }

    private void verifyProblem(MockServerWebExchange exchange, SecurityFailureCause cause, String detailFragment) {
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(problemWriter).write(eq(exchange), eq(cause), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).contains(detailFragment);
    }
}
