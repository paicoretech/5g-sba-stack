package com.paicore.sba.stack.transport.http2.discovery;

import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryQuery;
import com.paicore.sba.stack.api.nnrf.disc.NfDiscoveryResult;
import com.paicore.sba.stack.api.nnrf.disc.NfInstanceSelector;
import com.paicore.sba.stack.api.security.AuthorizationProvider;
import com.paicore.sba.stack.transport.http2.Http2Client;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@code Nnrf_NFDiscovery} client (3GPP TS 29.510) over an existing HTTP/2 connection to the NRF.
 *
 * <p>Target-NF agnostic: the caller describes what it is looking for with a
 * {@link NfDiscoveryQuery}, so the same client serves SMSF&rarr;UDM, SMSF&rarr;AMF or any
 * other pairing. It deliberately does not own a transport — a consuming module usually
 * already keeps one NRF connection per gateway for registration and token requests, and
 * opening a second one to the same NRF would be wasteful.</p>
 *
 * <p>Results are cached per query for the NRF-supplied {@code validityPeriod} (or a
 * configured default), which is what makes subscriber-scoped discovery affordable: UEs
 * sharing a routing indicator hit the same cache entry. {@link #invalidate(NfDiscoveryQuery)}
 * drops an entry after a transport failure so the next attempt re-resolves the target.</p>
 */
@Slf4j
public class Http2NfDiscoveryClient {

    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000L;
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final Http2Client nrfClient;
    private final String discoveryPath;
    private final long requestTimeoutMs;
    private final Duration defaultTtl;
    private final AuthorizationProvider authorizationProvider;
    private final String authorizationHeaderName;
    private final Supplier<Instant> clock;

    private final Map<String, NfDiscoveryResult> cache = new ConcurrentHashMap<>();

    @Builder
    public Http2NfDiscoveryClient(Http2Client nrfClient,
                           String discoveryPath,
                           long requestTimeoutMs,
                           Duration defaultTtl,
                           AuthorizationProvider authorizationProvider,
                           String authorizationHeaderName,
                           Supplier<Instant> clock) {
        this.nrfClient = nrfClient;
        this.discoveryPath = Objects.isNull(discoveryPath) || discoveryPath.isBlank()
                ? "/nnrf-disc/v1/nf-instances" : discoveryPath;
        this.requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : DEFAULT_REQUEST_TIMEOUT_MS;
        this.defaultTtl = defaultTtl;
        this.authorizationProvider = authorizationProvider;
        this.authorizationHeaderName = Objects.isNull(authorizationHeaderName) || authorizationHeaderName.isBlank()
                ? "authorization" : authorizationHeaderName.toLowerCase(Locale.ROOT);
        this.clock = Objects.isNull(clock) ? Instant::now : clock;
    }

    /**
     * Resolves the query, serving a cached result while it is still valid.
     *
     * @return the ranked result; never {@code null}
     * @throws IllegalStateException when the NRF connection is unusable or the search fails
     */
    public NfDiscoveryResult discover(NfDiscoveryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String key = query.cacheKey();
        Instant now = clock.get();

        NfDiscoveryResult cached = cache.get(key);
        if (Objects.nonNull(cached) && !cached.isEmpty() && !cached.isExpired(now)) {
            return cached;
        }

        NfDiscoveryResult fresh = search(query, now);
        if (fresh.isEmpty()) {
            cache.remove(key);
        } else {
            cache.put(key, fresh);
        }
        return fresh;
    }

    /** Drops the cached result for {@code query}, forcing the next call to re-resolve. */
    public void invalidate(NfDiscoveryQuery query) {
        if (Objects.nonNull(query)) {
            cache.remove(query.cacheKey());
        }
    }

    /** Drops every cached result. */
    public void invalidateAll() {
        cache.clear();
    }

    private NfDiscoveryResult search(NfDiscoveryQuery query, Instant now) {
        if (Objects.isNull(nrfClient) || !nrfClient.isConnected()) {
            throw new IllegalStateException("NRF client is not connected; cannot run NF discovery");
        }
        String path = discoveryPath + "?" + query.toQueryString();
        Http2Response response = await(nrfClient.send(Http2Request.builder()
                .method("GET")
                .path(path)
                .headers(buildHeaders())
                .build()), path);

        if (response.getStatus() / 100 != 2 || Objects.isNull(response.getBody())) {
            throw new IllegalStateException("NF discovery for " + query.getTargetNfType()
                    + " failed with HTTP " + response.getStatus());
        }

        NfDiscoveryResult result = NfInstanceSelector.parse(
                new String(response.getBody(), StandardCharsets.UTF_8),
                query.getPrimaryServiceName(), now, defaultTtl);

        if (result.isEmpty()) {
            log.warn("NF discovery for {} returned no usable instance (query: {})",
                    query.getTargetNfType(), query.toQueryString());
        } else {
            log.info("NF discovery for {} resolved {} instance(s); preferred apiRoot={}",
                    query.getTargetNfType(), result.instances().size(), result.preferredApiRoot().orElse(null));
        }
        return result;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", CONTENT_TYPE_JSON);
        if (Objects.nonNull(authorizationProvider)) {
            String token = authorizationProvider.authorizationHeader();
            if (Objects.nonNull(token) && !token.isBlank()) {
                headers.put(authorizationHeaderName, token);
            } else {
                log.debug("No authorization available for the NF discovery request");
            }
        }
        return headers;
    }

    private Http2Response await(CompletableFuture<Http2Response> future, String path) {
        if (Objects.isNull(future)) {
            throw new IllegalStateException("The NRF transport returned no response future");
        }
        try {
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting NF discovery response", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "Timed out after " + requestTimeoutMs + "ms awaiting NF discovery response for " + path, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("NF discovery request failed: " + e.getCause().getMessage(), e.getCause());
        }
    }
}
