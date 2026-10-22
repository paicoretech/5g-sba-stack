package com.paicore.sba.stack.security.webfilter;

import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.audit.SecurityAuditLogger;
import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.auth.Principal;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.security.problem.SecurityProblemWriter;
import com.paicore.sba.stack.security.tls.PeerCertValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@Slf4j
public class Http2SecurityWebFilter implements WebFilter, Ordered {

    public static final String EXCHANGE_ATTR_GATEWAY = "sba.security.gateway";
    public static final String EXCHANGE_ATTR_PRINCIPAL = "sba.security.principal";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final Http2SecurityFilterConfig config;
    private final GatewaySecurityLookup gatewayLookup;
    private final SecurityProblemWriter problemWriter;
    private final SecurityAuditLogger auditLogger;
    private final PeerCertValidator peerCertValidator;
    private final String exchangeAttrGateway;
    private final String exchangeAttrPrincipal;

    public Http2SecurityWebFilter(Http2SecurityFilterConfig config,
                                  GatewaySecurityLookup gatewayLookup,
                                  SecurityProblemWriter problemWriter,
                                  SecurityAuditLogger auditLogger,
                                  PeerCertValidator peerCertValidator,
                                  String exchangeAttrGateway,
                                  String exchangeAttrPrincipal) {
        this.config = config;
        this.gatewayLookup = gatewayLookup;
        this.problemWriter = problemWriter;
        this.auditLogger = auditLogger;
        this.peerCertValidator = peerCertValidator;
        this.exchangeAttrGateway = Objects.requireNonNullElse(exchangeAttrGateway, EXCHANGE_ATTR_GATEWAY);
        this.exchangeAttrPrincipal = Objects.requireNonNullElse(exchangeAttrPrincipal, EXCHANGE_ATTR_PRINCIPAL);
    }

    public Http2SecurityWebFilter(Http2SecurityFilterConfig config,
                                  GatewaySecurityLookup gatewayLookup,
                                  SecurityProblemWriter problemWriter,
                                  SecurityAuditLogger auditLogger,
                                  PeerCertValidator peerCertValidator) {
        this(config, gatewayLookup, problemWriter, auditLogger, peerCertValidator,
                EXCHANGE_ATTR_GATEWAY, EXCHANGE_ATTR_PRINCIPAL);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (!config.isSecurityEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (isBypassPath(path)) {
            return chain.filter(exchange);
        }

        OptionalInt networkIdOpt = extractNetworkId(path);
        if (networkIdOpt.isEmpty()) {
            return reject(exchange, SecurityFailureCause.UNKNOWN_GATEWAY,
                    "Request path does not carry a valid networkId", null);
        }
        int networkId = networkIdOpt.getAsInt();

        Optional<GatewaySecurityContext> bundleOpt = gatewayLookup.findByNetworkId(networkId);
        if (bundleOpt.isEmpty()) {
            return reject(exchange, SecurityFailureCause.UNKNOWN_GATEWAY,
                    "No gateway registered for networkId " + networkId, null);
        }
        GatewaySecurityContext bundle = bundleOpt.get();

        Optional<X509Certificate> peerCertOpt = peerCertValidator.extract(exchange);
        Mono<Void> mtlsRejection = enforceMtls(exchange, bundle, peerCertOpt);
        if (Objects.nonNull(mtlsRejection)) {
            return mtlsRejection;
        }

        if (!bundle.isOauth2Enabled()) {
            Principal anonymous = Principal.builder().build();
            return accept(exchange, chain, bundle, anonymous);
        }

        Optional<String> tokenOpt = extractBearer(exchange.getRequest());
        if (tokenOpt.isEmpty()) {
            return reject(exchange, SecurityFailureCause.MISSING_AUTHORIZATION_HEADER,
                    "Authorization header is missing", null);
        }
        String token = tokenOpt.get();

        Optional<Authenticator> authOpt = gatewayLookup.authenticatorForNetworkId(networkId);
        if (authOpt.isEmpty()) {
            return reject(exchange, SecurityFailureCause.JWKS_UNAVAILABLE,
                    "No JWKS endpoint configured for gateway", token);
        }
        Authenticator authenticator = authOpt.get();

        return Mono.fromCallable(() -> authenticator.authenticate(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(principal -> {
                    if (peerCertOpt.isPresent() && requiresMtls(bundle)
                            && !peerCertValidator.sanContainsPrincipalId(peerCertOpt.get(), principal.getId())) {
                        return reject(exchange, SecurityFailureCause.INVALID_TOKEN,
                                "JWT sub does not match SAN URI in client certificate (TS 33.501 §13.4.1.3.1.1)",
                                token);
                    }
                    return accept(exchange, chain, bundle, principal);
                })
                .onErrorResume(AuthenticationException.class,
                        ex -> reject(exchange, causeOrDefault(ex), ex.getDetails(), token));
    }

    private Mono<Void> enforceMtls(ServerWebExchange exchange, GatewaySecurityContext bundle,
                                   Optional<X509Certificate> peerCertOpt) {
        if (!requiresMtls(bundle)) {
            return null;
        }
        if (peerCertOpt.isEmpty()) {
            return reject(exchange, SecurityFailureCause.INVALID_TOKEN,
                    "Gateway requires mTLS but no client certificate was presented", null);
        }
        String expectedPath = bundle.expectedPeerCertificatePath();
        if (!peerCertValidator.matchesPinned(peerCertOpt.get(), expectedPath)) {
            return reject(exchange, SecurityFailureCause.INVALID_TOKEN,
                    "Client certificate does not match expected AMF certificate (pinning)", null);
        }
        return null;
    }

    private static boolean requiresMtls(GatewaySecurityContext bundle) {
        return bundle.isRequireMtls();
    }

    private boolean isBypassPath(String path) {
        List<String> patterns = config.getBypassPaths();
        if (patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            String trimmed = Objects.isNull(pattern) ? "" : pattern.trim();
            if (!trimmed.isEmpty() && PATH_MATCHER.match(trimmed, path)) {
                return true;
            }
        }
        return false;
    }

    private OptionalInt extractNetworkId(String path) {
        String basePath = config.getSbiServerBasePath();
        if (Objects.isNull(basePath) || basePath.isBlank() || Objects.isNull(path)) {
            return OptionalInt.empty();
        }
        String normalisedBase = basePath.endsWith("/") ? basePath : basePath + "/";
        if (!path.startsWith(normalisedBase)) {
            return OptionalInt.empty();
        }
        String tail = path.substring(normalisedBase.length());
        if (tail.isEmpty()) {
            return OptionalInt.empty();
        }
        int slash = tail.indexOf('/');
        String segment = slash >= 0 ? tail.substring(0, slash) : tail;
        if (segment.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(segment));
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }

    private Optional<String> extractBearer(ServerHttpRequest request) {
        String headerName = config.getBearerHeaderName();
        String header = request.getHeaders().getFirst(headerName);
        if (Objects.isNull(header) || header.isBlank()) {
            return Optional.empty();
        }
        String prefix = config.getBearerScheme() + " ";
        if (!header.startsWith(prefix)) {
            return Optional.empty();
        }
        String token = header.substring(prefix.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private Mono<Void> accept(ServerWebExchange exchange, WebFilterChain chain,
                              GatewaySecurityContext bundle, Principal principal) {
        exchange.getAttributes().put(exchangeAttrGateway, bundle);
        exchange.getAttributes().put(exchangeAttrPrincipal, principal);
        auditLogger.logSuccess(exchange.getRequest(), bundle.networkId(), principal);
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, SecurityFailureCause cause,
                              String detail, String token) {
        auditLogger.logFailure(exchange.getRequest(), cause, token, detail);
        return problemWriter.write(exchange, cause, detail);
    }

    private static SecurityFailureCause causeOrDefault(AuthenticationException ex) {
        return Objects.requireNonNullElse(ex.getFailureCause(), SecurityFailureCause.INVALID_TOKEN);
    }
}
