package com.paicore.sba.stack.security.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.jwk.JWKSet;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caches RFC 7517 JWK Sets with TTL + dedupe of concurrent miss-path fetches.
 * Transport is delegated to a {@link JwkSetLoader} so this class stays free of
 * HTTP/IO concerns; an HTTP/2 + mTLS transport is provided by
 * {@link HttpJwkSetLoader} via the convenience constructor.
 *
 * <p>Requires {@code com.github.ben-manes.caffeine:caffeine} on the consumer
 * classpath (optional in this module).
 */
public class JwksKeyStore {

    private static final long DEFAULT_MAXIMUM_SIZE = 32L;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String SINGLE_KEY = "default";

    private final JwkSetLoader loader;
    private final Cache<String, JWKSet> cache;
    private final AtomicReference<CompletableFuture<JWKSet>> inFlight = new AtomicReference<>();
    private final Duration awaitTimeout;
    private final String cacheKey;
    private final String sourceLabel;

    public JwksKeyStore(JwkSetLoader loader) {
        this(loader, SINGLE_KEY, SINGLE_KEY, DEFAULT_AWAIT_TIMEOUT);
    }

    public JwksKeyStore(JwkSetLoader loader, String cacheKey, String sourceLabel, Duration awaitTimeout) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.cacheKey = Objects.requireNonNullElse(cacheKey, SINGLE_KEY);
        this.sourceLabel = Objects.requireNonNullElse(sourceLabel, this.cacheKey);
        this.awaitTimeout = Objects.requireNonNullElse(awaitTimeout, DEFAULT_AWAIT_TIMEOUT);
        this.cache = Caffeine.newBuilder()
                .maximumSize(DEFAULT_MAXIMUM_SIZE)
                .expireAfterWrite(DEFAULT_TTL)
                .build();
    }

    public static JwksKeyStore forHttp2(Http2ClientSettings settings) {
        Objects.requireNonNull(settings, "settings");
        HttpJwkSetLoader transport = new HttpJwkSetLoader(settings, 2);
        String key = Objects.requireNonNullElse(settings.getKey(),
                settings.getHost() + ":" + settings.getPort() + settings.getPath());
        String label = settings.getHost() + ":" + settings.getPort() + settings.getPath();
        return new JwksKeyStore(transport, key, label, DEFAULT_AWAIT_TIMEOUT);
    }

    public JWKSet get() {
        JWKSet cached = cache.getIfPresent(cacheKey);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        return await(acquireInFlight());
    }

    /**
     * Returns the in-flight load, starting one only if this caller wins the race to publish it.
     *
     * <p>Deliberately a compare-and-set loop rather than {@code updateAndGet}: that method may
     * invoke its function several times under contention, and starting a fetch is a side effect.
     * Losing threads would still have submitted a load before their CAS failed, so concurrent
     * miss-path callers would each hit the JWKS endpoint — the very thing this class dedupes.</p>
     */
    private CompletableFuture<JWKSet> acquireInFlight() {
        while (true) {
            CompletableFuture<JWKSet> existing = inFlight.get();
            if (Objects.nonNull(existing) && !existing.isDone()) {
                return existing;
            }
            CompletableFuture<JWKSet> candidate = new CompletableFuture<>();
            if (inFlight.compareAndSet(existing, candidate)) {
                startFetch(candidate);
                return candidate;
            }
            // Another caller published first; loop to pick up their future.
        }
    }

    public JWKSet refresh() {
        invalidate(cacheKey);
        return get();
    }

    public void invalidate(String key) {
        if (Objects.isNull(key)) {
            return;
        }
        cache.invalidate(key);
        inFlight.set(null);
    }

    /** Runs the load off-thread and settles {@code target}, which callers are already awaiting. */
    private void startFetch(CompletableFuture<JWKSet> target) {
        target.whenComplete((value, error) -> inFlight.compareAndSet(target, null));
        CompletableFuture.runAsync(() -> {
            try {
                JWKSet set = loadOrWrap();
                cache.put(cacheKey, set);
                target.complete(set);
            } catch (RuntimeException ex) {
                target.completeExceptionally(ex);
            }
        });
    }

    private JWKSet loadOrWrap() {
        try {
            JWKSet set = loader.load();
            if (Objects.isNull(set)) {
                throw new AuthenticationException("Loader returned null JWKSet for " + sourceLabel,
                        SecurityFailureCause.JWKS_UNAVAILABLE);
            }
            return set;
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AuthenticationException("Failed to load JWKS from " + sourceLabel,
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex);
        }
    }

    private JWKSet await(CompletableFuture<JWKSet> future) {
        try {
            return future.get(awaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new AuthenticationException("Timed out waiting for JWKS from " + sourceLabel,
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("Interrupted while waiting for JWKS from " + sourceLabel,
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex);
        } catch (CompletionException | java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof AuthenticationException ae) {
                throw ae;
            }
            throw new AuthenticationException("Failed to load JWKS from " + sourceLabel,
                    SecurityFailureCause.JWKS_UNAVAILABLE, Objects.isNull(cause) ? ex : cause);
        }
    }
}
