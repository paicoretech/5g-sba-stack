package com.paicore.sba.stack.transport.http2.n21;

import com.paicore.sba.stack.api.n21.N21Client;
import com.paicore.sba.stack.api.n21.N21Configuration;
import com.paicore.sba.stack.api.n21.N21Paths;
import com.paicore.sba.stack.api.n21.N21RegistrationMapper;
import com.paicore.sba.stack.api.n21.SmsfContext;
import com.paicore.sba.stack.api.n21.SmsfRegistrationResponse;
import com.paicore.sba.stack.api.nnrf.disc.DiscoveredNfInstance;
import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryQuery;
import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryResult;
import com.paicore.sba.stack.api.security.AuthorizationProvider;
import com.paicore.sba.stack.openapi.model.ProblemDetails;
import com.paicore.sba.stack.openapi.model.SmsfRegistration;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.transport.http2.Http2Client;
import com.paicore.sba.stack.transport.http2.NettyHttp2Client;
import com.paicore.sba.stack.transport.http2.config.ConnectionPoolSettings;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import com.paicore.sba.stack.transport.http2.discovery.Http2NfDiscoveryClient;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * HTTP/2 implementation of {@link N21Client} (SMSF &rarr; UDM, 3GPP TS 29.503).
 *
 * <p>Owns its transports to the UDM, opened on demand. When NRF discovery is enabled the
 * UDM is resolved through {@link Http2NfDiscoveryClient} over an injected, already-connected
 * NRF client — typically the one the consuming module already runs per gateway — so no second
 * connection to the NRF is opened.</p>
 *
 * <p>Discovery is subscriber-aware when the UE context carries a routing indicator or a UDM
 * group id: the NRF then steers each UE to the UDM instance that serves it. Because that can
 * yield different endpoints for different subscribers, the client keeps a small bounded map
 * of open transports keyed by apiRoot rather than a single connection.</p>
 *
 * <p>Requests are sent once and never retried: a transport failure propagates to the caller,
 * which decides how to report it. The broken connection and its discovery entry are discarded
 * so the next request resolves the UDM afresh. Whatever the UDM answers — including a
 * {@code 4xx} — is returned as-is, since a rejected registration is an answer, not a failure.</p>
 */
@Slf4j
public class N21ClientImpl implements N21Client {

    private static final int DEFAULT_PORT = 8443;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000L;
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String SCHEME_HTTPS = "https";

    private final N21Configuration config;
    private final SmsfContext smsfContext;
    private final AuthorizationProvider authorizationProvider;
    private final Function<Http2ClientSettings, Http2Client> udmClientFactory;
    private final Http2NfDiscoveryClient discoveryClient;

    /** Open transports keyed by apiRoot, in insertion order so the eldest can be evicted. */
    private final Map<String, Connection> connections = new LinkedHashMap<>();

    public N21ClientImpl(N21Configuration config,
                         SmsfContext smsfContext,
                         Http2Client nrfClient,
                         AuthorizationProvider authorizationProvider) {
        this(config, smsfContext, nrfClient, authorizationProvider, NettyHttp2Client::new);
    }

    // Package-private seam: lets tests inject a mock UDM transport instead of a real socket.
    N21ClientImpl(N21Configuration config,
                  SmsfContext smsfContext,
                  Http2Client nrfClient,
                  AuthorizationProvider authorizationProvider,
                  Function<Http2ClientSettings, Http2Client> udmClientFactory) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.smsfContext = Objects.requireNonNull(smsfContext, "smsfContext must not be null");
        this.authorizationProvider = authorizationProvider;
        this.udmClientFactory = Objects.requireNonNull(udmClientFactory, "udmClientFactory must not be null");
        Duration discoveryCacheTtl = config.getDiscoveryCacheTtlSeconds() > 0
                ? Duration.ofSeconds(config.getDiscoveryCacheTtlSeconds())
                : null;
        AuthorizationProvider discoveryAuthorizationProvider = config.isOauth2Enabled()
                ? authorizationProvider
                : null;
        this.discoveryClient = config.isNrfDiscoveryEnabled()
                ? Http2NfDiscoveryClient.builder()
                .nrfClient(nrfClient)
                .discoveryPath(config.getDiscoveryPath())
                .requestTimeoutMs(config.getRequestTimeoutMs())
                .defaultTtl(discoveryCacheTtl)
                .authorizationProvider(discoveryAuthorizationProvider)
                .authorizationHeaderName(config.getAuthorizationHeaderName())
                .build()
                : null;
    }

    /**
     * Opens the transport to the UDM that serves no particular subscriber, so a consumer can
     * fail fast at startup if the UDM is unreachable. Subscriber-scoped endpoints are opened
     * lazily on first use.
     */
    @Override
    public synchronized void connect() {
        if (isConnected()) {
            log.debug("N21 UDM client already connected");
            return;
        }
        acquire(buildQuery(null));
    }

    @Override
    public synchronized boolean isConnected() {
        return connections.values().stream().anyMatch(c -> c.client().isConnected());
    }

    @Override
    public SmsfRegistration buildRegistration(UeSmsContextData ueContext) {
        return N21RegistrationMapper.buildRegistration(smsfContext, ueContext);
    }

    @Override
    public SmsfRegistrationResponse registerSubscriber(String path, UeSmsContextData ueContext) {
        Objects.requireNonNull(ueContext, "ueContext must not be null");
        SmsfRegistration payload = buildRegistration(ueContext);
        byte[] body = SdkConverter.valueAsString(payload).getBytes(StandardCharsets.UTF_8);
        return exchange("PUT", pathOr(path, config.getRegistrationPath()), ueContext.getSupi(), body, ueContext);
    }

    @Override
    public SmsfRegistrationResponse deregisterSubscriber(String path, UeSmsContextData ueContext) {
        Objects.requireNonNull(ueContext, "ueContext must not be null");
        return exchange("DELETE", pathOr(path, config.getDeregistrationPath()), ueContext.getSupi(), null, ueContext);
    }

    @Override
    public SmsfRegistrationResponse getRegistration(String path, String supi) {
        UeSmsContextData ueContext = new UeSmsContextData();
        ueContext.setSupi(supi);
        return exchange("GET", pathOr(path, config.getSubscriberQueryPath()), supi, null, ueContext);
    }

    @Override
    public synchronized void close() {
        connections.values().forEach(Connection::closeQuietly);
        connections.clear();
        if (Objects.nonNull(discoveryClient)) {
            discoveryClient.invalidateAll();
        }
    }

    // --- request/response ----------------------------------------------------

    /**
     * Sends the request once and returns whatever the UDM answered.
     *
     * <p>The request is not retried: a failure propagates to the caller, which reports it.
     * The failed connection and its discovery entry are dropped so the <em>next</em> request
     * resolves the UDM again instead of reusing a transport that is known to be broken.</p>
     */
    private SmsfRegistrationResponse exchange(String method, String template, String ueId,
                                              byte[] body, UeSmsContextData ueContext) {
        NfDiscoveryQuery query = buildQuery(ueContext);

        Connection connection;
        try {
            connection = acquire(query);
        } catch (RuntimeException ex) {
            // A UDM we could not reach must not stay cached for the rest of its validity period.
            invalidateDiscovery(query);
            throw ex;
        }

        String path = N21Paths.resolve(resourceTemplate(template, connection), ueId);
        try {
            return toResponse(await(connection.client().send(buildRequest(connection, method, path, body))));
        } catch (RuntimeException ex) {
            dropConnection(connection);
            invalidateDiscovery(query);
            throw ex;
        }
    }

    /**
     * Resolves the {@code /{apiName}/{apiVersion}/...} portion of the URI (TS 29.501 clause
     * 4.4.1).
     *
     * <p>A configured template wins, since an operator overriding it knows their UDM. Otherwise
     * it is composed from the {@code serviceName} and {@code apiVersionInUri} the NRF advertised
     * — the NRF never returns the resource itself, only the apiRoot and the API version, so
     * hard-coding {@code v1} would break against a UDM serving another version.</p>
     */
    private static String resourceTemplate(String configured, Connection connection) {
        if (Objects.nonNull(configured) && !configured.isBlank()) {
            return configured;
        }
        if (Objects.nonNull(connection.apiVersion())) {
            return N21Paths.resourceTemplate(connection.serviceName(), connection.apiVersion());
        }
        return N21Paths.SMSF_3GPP_ACCESS_REGISTRATION;
    }

    private Http2Request buildRequest(Connection connection, String method, String path, byte[] body) {
        Http2Request.Http2RequestBuilder builder = Http2Request.builder()
                .method(method)
                .path(joinPath(connection.basePath(), path))
                .headers(buildHeaders(Objects.nonNull(body)));
        if (Objects.nonNull(body)) {
            builder.body(body);
        }
        return builder.build();
    }

    private Map<String, String> buildHeaders(boolean hasBody) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", CONTENT_TYPE_JSON);
        if (hasBody) {
            headers.put("content-type", CONTENT_TYPE_JSON);
        }
        if (config.isOauth2Enabled() && Objects.nonNull(authorizationProvider)) {
            String token = authorizationProvider.authorizationHeader();
            if (Objects.nonNull(token) && !token.isBlank()) {
                headers.put(config.getAuthorizationHeaderName().toLowerCase(Locale.ROOT), token);
            } else {
                log.warn("OAuth2 enabled but the authorization provider returned no value");
            }
        }
        return headers;
    }

    private SmsfRegistrationResponse toResponse(Http2Response response) {
        int status = response.getStatus();
        boolean success = status / 100 == 2;
        String rawBody = Objects.nonNull(response.getBody())
                ? new String(response.getBody(), StandardCharsets.UTF_8) : null;

        SmsfRegistrationResponse.SmsfRegistrationResponseBuilder out = SmsfRegistrationResponse.builder()
                .statusCode(status)
                .success(success)
                .location(Objects.nonNull(response.getHeaders()) ? response.getHeaders().get("location") : null)
                .rawBody(rawBody);

        if (Objects.nonNull(rawBody) && !rawBody.isBlank()) {
            if (success) {
                tryParse(rawBody, SmsfRegistration.class).ifPresent(out::registration);
            } else {
                tryParse(rawBody, ProblemDetails.class).ifPresent(out::problem);
            }
        }
        return out.build();
    }

    private <T> Optional<T> tryParse(String json, Class<T> type) {
        try {
            return Optional.ofNullable(SdkConverter.stringToObject(json, type));
        } catch (Exception ex) {
            log.debug("Could not parse response body as {}: {}", type.getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    // --- endpoint resolution -------------------------------------------------

    /**
     * Builds the discovery query for this exchange. Subscriber selectors are only added when
     * the UE context actually carries them, so UEs without a routing indicator or UDM group
     * share one cache entry (and one connection) instead of forcing a lookup each.
     */
    private NfDiscoveryQuery buildQuery(UeSmsContextData ueContext) {
        if (!config.isNrfDiscoveryEnabled()) {
            return null;
        }
        NfDiscoveryQuery.NfDiscoveryQueryBuilder builder = NfDiscoveryQuery.builder()
                .targetNfType(config.getTargetNfType())
                .requesterNfType(config.getRequesterNfType())
                .requesterNfInstanceId(config.getRequesterNfInstanceId())
                .serviceName(config.getTargetServiceName());

        if (config.isSubscriberAwareDiscovery() && Objects.nonNull(ueContext)) {
            builder.routingIndicator(ueContext.getRoutingIndicator());
            if (Objects.nonNull(ueContext.getUdmGroupId()) && !ueContext.getUdmGroupId().isBlank()) {
                builder.groupId(ueContext.getUdmGroupId());
            }
        }
        return builder.build();
    }

    /** Returns an open transport for the endpoint this query resolves to, opening one if needed. */
    private synchronized Connection acquire(NfDiscoveryQuery query) {
        DiscoveredNfInstance target = resolveTarget(query);
        String apiRoot = target.apiRoot();
        Connection existing = connections.get(apiRoot);
        if (Objects.nonNull(existing) && existing.client().isConnected()) {
            return existing;
        }
        if (Objects.nonNull(existing)) {
            existing.closeQuietly();
            connections.remove(apiRoot);
        }
        evictIfFull();

        Endpoint endpoint = Endpoint.parse(apiRoot, DEFAULT_PORT);
        Http2Client client = udmClientFactory.apply(buildSettings(endpoint));
        client.connect();
        Connection connection = new Connection(apiRoot, endpoint.basePath(), client,
                target.serviceName(), target.apiVersion());
        connections.put(apiRoot, connection);
        log.info("N21 UDM client connected to {}:{} (tls={}, mTLS={}, discovery={})",
                endpoint.host(), endpoint.port(), endpoint.isSecure() || config.isMtlsEnabled(),
                config.isMtlsEnabled(), config.isNrfDiscoveryEnabled());
        return connection;
    }

    /** Keeps the number of simultaneously open UDM transports within {@code maxEndpoints}. */
    private void evictIfFull() {
        int max = Math.max(1, config.getMaxEndpoints());
        while (connections.size() >= max) {
            var eldest = connections.entrySet().iterator().next();
            log.debug("Evicting N21 UDM connection to {} (endpoint limit {} reached)", eldest.getKey(), max);
            eldest.getValue().closeQuietly();
            connections.remove(eldest.getKey());
        }
    }

    private void dropConnection(Connection connection) {
        synchronized (this) {
            Connection current = connections.get(connection.apiRoot());
            if (current == connection) {
                connections.remove(connection.apiRoot());
            }
        }
        connection.closeQuietly();
    }

    private void invalidateDiscovery(NfDiscoveryQuery query) {
        if (Objects.nonNull(discoveryClient)) {
            discoveryClient.invalidate(query);
        }
    }

    /**
     * Resolves the UDM to talk to. With discovery disabled the target is the configured apiRoot
     * and nothing else is known about it, so the API version stays {@code null} and the caller
     * falls back to the configured or default path.
     */
    private DiscoveredNfInstance resolveTarget(NfDiscoveryQuery query) {
        if (Objects.isNull(query) || Objects.isNull(discoveryClient)) {
            if (Objects.isNull(config.getApiRoot()) || config.getApiRoot().isBlank()) {
                throw new IllegalStateException("N21Configuration.apiRoot is required when NRF discovery is disabled");
            }
            return new DiscoveredNfInstance(null, config.getApiRoot(), null, null,
                    DiscoveredNfInstance.DEFAULT_PRIORITY, 0, 0);
        }
        NfDiscoveryResult result = discoveryClient.discover(query);
        return result.preferred().orElseThrow(() -> new IllegalStateException(
                "NRF discovery returned no usable " + config.getTargetNfType() + " instance"));
    }

    // --- transport settings --------------------------------------------------

    private Http2ClientSettings buildSettings(Endpoint endpoint) {
        ConnectionPoolSettings.ConnectionPoolSettingsBuilder pool = ConnectionPoolSettings.builder();
        if (config.getConnectionTimeoutMs() > 0) {
            pool.connectTimeoutMillis(config.getConnectionTimeoutMs());
        }
        if (config.getRequestTimeoutMs() > 0) {
            pool.readTimeoutMillis(config.getRequestTimeoutMs());
        }
        if (config.getIdleTimeoutMs() > 0) {
            pool.idleTimeoutSeconds(Math.max(1, config.getIdleTimeoutMs() / 1000));
        }
        return Http2ClientSettings.builder()
                .host(endpoint.host())
                .port(endpoint.port())
                .tls(buildTls(endpoint))
                .pool(pool.build())
                .build();
    }

    /**
     * TLS follows the apiRoot scheme as well as the mTLS flag: an {@code https} apiRoot needs
     * a TLS handshake even when the UDM does not ask for a client certificate.
     */
    private TlsSettings buildTls(Endpoint endpoint) {
        boolean tlsEnabled = config.isMtlsEnabled() || endpoint.isSecure();
        TlsSettings.TlsSettingsBuilder<?, ?> builder = TlsSettings.builder()
                .enabled(tlsEnabled)
                .verifyHostname(tlsEnabled && config.isVerifyHostnameEffective());
        if (config.isMtlsEnabled()) {
            builder.keyStorePath(config.getClientCertificatePath())
                    .keyPath(config.getClientPrivateKeyPath());
        }
        if (tlsEnabled && Objects.nonNull(config.getTruststoreCaPath()) && !config.getTruststoreCaPath().isBlank()) {
            builder.trustStorePath(config.getTruststoreCaPath());
        }
        return builder.build();
    }

    // --- helpers -------------------------------------------------------------

    private Http2Response await(CompletableFuture<Http2Response> future) {
        if (Objects.isNull(future)) {
            throw new IllegalStateException("The UDM transport returned no response future");
        }
        long timeout = config.getRequestTimeoutMs() > 0 ? config.getRequestTimeoutMs() : DEFAULT_REQUEST_TIMEOUT_MS;
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting N21 response", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out after " + timeout + "ms awaiting N21 response", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("N21 request failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static String pathOr(String path, String fallback) {
        return Objects.isNull(path) || path.isBlank() ? fallback : path;
    }

    private static String joinPath(String base, String path) {
        String b = Objects.isNull(base) ? "" : base;
        String p = Objects.isNull(path) ? "/" : path;
        if (b.isEmpty()) {
            return p.startsWith("/") ? p : "/" + p;
        }
        if (b.endsWith("/") && p.startsWith("/")) {
            return b + p.substring(1);
        }
        if (!b.endsWith("/") && !p.startsWith("/")) {
            return b + "/" + p;
        }
        return b + p;
    }

    /** An open transport to one UDM endpoint, plus what the NRF said about its API. */
    private record Connection(String apiRoot, String basePath, Http2Client client,
                              String serviceName, String apiVersion) {

        void closeQuietly() {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Error while closing the N21 UDM client for {}: {}", apiRoot, ex.getMessage());
            }
        }
    }

    /** Parsed apiRoot: scheme, host, port and optional base path. */
    private record Endpoint(String scheme, String host, int port, String basePath) {

        static Endpoint parse(String apiRoot, int defaultPort) {
            String value = apiRoot.trim();
            if (!value.contains("://")) {
                value = SCHEME_HTTPS + "://" + value;
            }
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (Objects.isNull(host) || host.isBlank()) {
                throw new IllegalArgumentException("Cannot resolve host from apiRoot: " + apiRoot);
            }
            String scheme = Objects.isNull(uri.getScheme())
                    ? SCHEME_HTTPS : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
            String path = uri.getPath();
            String base = (Objects.isNull(path) || path.equals("/")) ? "" : stripTrailingSlash(path);
            return new Endpoint(scheme, host, port, base);
        }

        boolean isSecure() {
            return SCHEME_HTTPS.equals(scheme);
        }

        private static String stripTrailingSlash(String path) {
            return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        }
    }
}
