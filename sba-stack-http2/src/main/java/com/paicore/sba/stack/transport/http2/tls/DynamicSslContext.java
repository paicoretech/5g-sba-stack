package com.paicore.sba.stack.transport.http2.tls;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class DynamicSslContext extends SslContext {

    private final Supplier<SslContext> delegateSupplier;
    private final SslContext placeholder;

    public DynamicSslContext(Supplier<SslContext> delegateSupplier, SslContext placeholder) {
        this.delegateSupplier = Objects.requireNonNull(delegateSupplier, "delegateSupplier");
        this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
    }

    private SslContext currentOrPlaceholder() {
        SslContext current = delegateSupplier.get();
        return Objects.nonNull(current) ? current : placeholder;
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    public List<String> cipherSuites() {
        return currentOrPlaceholder().cipherSuites();
    }

    @Override
    @SuppressWarnings("deprecation")
    public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
        return currentOrPlaceholder().applicationProtocolNegotiator();
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator alloc) {
        return currentOrPlaceholder().newEngine(alloc);
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
        return currentOrPlaceholder().newEngine(alloc, peerHost, peerPort);
    }

    @Override
    public SSLSessionContext sessionContext() {
        return currentOrPlaceholder().sessionContext();
    }
}
