package com.paicore.sba.stack.transport.http2.tls;

import io.netty.handler.ssl.SslContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import reactor.netty.http.HttpProtocol;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
public class Http2NettyTlsCustomizer
        implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    private static final long DEFAULT_MAX_CONCURRENT_STREAMS = 256L;

    private final SslContext placeholderContext;
    private final Supplier<SslContext> sslContextSupplier;
    private final long handshakeTimeoutMs;
    private final long maxConcurrentStreams;

    public Http2NettyTlsCustomizer(SslContext placeholderContext,
                                   Supplier<SslContext> sslContextSupplier,
                                   long handshakeTimeoutMs) {
        this(placeholderContext, sslContextSupplier, handshakeTimeoutMs, DEFAULT_MAX_CONCURRENT_STREAMS);
    }

    public Http2NettyTlsCustomizer(SslContext placeholderContext,
                                   Supplier<SslContext> sslContextSupplier,
                                   long handshakeTimeoutMs,
                                   long maxConcurrentStreams) {
        this.placeholderContext = Objects.requireNonNull(placeholderContext, "placeholderContext");
        this.sslContextSupplier = Objects.requireNonNull(sslContextSupplier, "sslContextSupplier");
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        Duration handshakeTimeout = Duration.ofMillis(handshakeTimeoutMs);
        SslContext dynamicCtx = new DynamicSslContext(sslContextSupplier, placeholderContext);
        factory.addServerCustomizers(httpServer -> httpServer
                .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
                .http2Settings(spec -> spec.maxConcurrentStreams(maxConcurrentStreams))
                .secure(spec -> spec.sslContext(dynamicCtx).handshakeTimeout(handshakeTimeout)));
        log.info("Reactor Netty configured with TLS + ALPN h2 (dynamic SslContext with placeholder fallback, handshakeTimeout={}ms, maxConcurrentStreams={})",
                handshakeTimeoutMs, maxConcurrentStreams);
    }
}
