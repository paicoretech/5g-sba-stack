package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.transport.http2.NettyHttp2Client;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class HttpJwkSetLoader implements JwkSetLoader {

    private final Http2ClientSettings settings;
    private final int maxAttempts;
    private NettyHttp2Client httpClient;

    public HttpJwkSetLoader(Http2ClientSettings settings, int maxAttempts) {
        this(settings, maxAttempts, null);
    }

    HttpJwkSetLoader(Http2ClientSettings settings, int maxAttempts, NettyHttp2Client preconnectedClient) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.httpClient = preconnectedClient;
    }

    @Override
    public JWKSet load() {
        AuthenticationException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ensureConnected();
                return fetchOnce();
            } catch (TransportFailure ex) {
                last = ex.cause;
                resetClient();
            } catch (AuthenticationException ex) {
                last = ex;
            }
        }
        throw Objects.requireNonNullElseGet(last, () -> new AuthenticationException(
                "Failed to load JWKS from " + settings.getHost(),
                SecurityFailureCause.JWKS_UNAVAILABLE));
    }

    private synchronized void ensureConnected() {
        if (Objects.nonNull(httpClient) && httpClient.isConnected()) {
            return;
        }
        resetClient();
        try {
            NettyHttp2Client client = new NettyHttp2Client(settings);
            client.connect();
            this.httpClient = client;
        } catch (Exception ex) {
            throw new TransportFailure(new AuthenticationException(
                    "Failed to connect to JWKS endpoint " + settings.getHost() + ":" + settings.getPort(),
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex));
        }
    }

    private synchronized void resetClient() {
        if (Objects.isNull(httpClient)) {
            return;
        }
        try {
            httpClient.close();
        } catch (Exception ex) {
            log.debug("Error closing HTTP/2 JWKS client: {}", ex.getMessage());
        } finally {
            httpClient = null;
        }
    }

    private JWKSet fetchOnce() {
        Http2Request request = Http2Request.builder()
                .method("GET")
                .path(settings.getPath())
                .build();

        try {
            CompletableFuture<Http2Response> future = httpClient.send(request);
            long readTimeoutMs = Objects.isNull(settings.getPool()) ? 5_000L
                    : Math.max(1_000L, settings.getPool().getReadTimeoutMillis());
            Http2Response response = future.get(readTimeoutMs, TimeUnit.MILLISECONDS);

            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new AuthenticationException(
                        "JWKS endpoint " + settings.getHost() + " returned HTTP " + response.getStatus(),
                        SecurityFailureCause.JWKS_UNAVAILABLE);
            }

            byte[] bodyBytes = response.getBody();
            if (Objects.isNull(bodyBytes) || bodyBytes.length == 0) {
                throw new AuthenticationException(
                        "JWKS endpoint " + settings.getHost() + " returned empty body",
                        SecurityFailureCause.JWKS_UNAVAILABLE);
            }
            return parseJwkSet(new String(bodyBytes, StandardCharsets.UTF_8), settings.getHost());

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException(
                    "Interrupted while fetching JWKS from " + settings.getHost(),
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex);

        } catch (ExecutionException ex) {
            throw new TransportFailure(new AuthenticationException(
                    "Failed to fetch JWKS from " + settings.getHost(),
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex));

        } catch (TimeoutException ex) {
            throw new TransportFailure(new AuthenticationException(
                    "Timed out fetching JWKS from " + settings.getHost(),
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex));
        }
    }

    private static final class TransportFailure extends RuntimeException {
        private final transient AuthenticationException cause;

        private TransportFailure(AuthenticationException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    private static JWKSet parseJwkSet(String body, String host) {
        try {
            return JWKSet.parse(body);
        } catch (ParseException ex) {
            throw new AuthenticationException(
                    "Failed to parse JWKS payload from " + host,
                    SecurityFailureCause.JWKS_UNAVAILABLE, ex);
        }
    }
}
