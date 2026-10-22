package com.paicore.sba.stack.transport.http2.tls;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicSslContextTest {

    private static SslContext realContext;
    private SslContext placeholder;

    @BeforeAll
    static void mintRealServerContext() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("dynamic-ssl-test");
        realContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();
    }

    @BeforeEach
    void setUp() {
        placeholder = mock(SslContext.class);
    }

    @Test
    @DisplayName("isClient is always false (the context is server-side only)")
    void isClient_ShouldAlwaysReturnFalse() {
        DynamicSslContext ctx = new DynamicSslContext(() -> realContext, placeholder);

        assertThat(ctx.isClient()).isFalse();
        verifyNoInteractions(placeholder);
    }

    @Test
    @DisplayName("cipherSuites delegates to supplier value when present")
    void cipherSuites_ShouldDelegateToSupplierWhenAvailable() {
        DynamicSslContext ctx = new DynamicSslContext(() -> realContext, placeholder);

        List<String> suites = ctx.cipherSuites();

        assertThat(suites).isEqualTo(realContext.cipherSuites());
        verifyNoInteractions(placeholder);
    }

    @Test
    @DisplayName("cipherSuites falls back to the placeholder when supplier returns null")
    void cipherSuites_ShouldFallBackToPlaceholderWhenSupplierReturnsNull() {
        when(placeholder.cipherSuites()).thenReturn(List.of("TLS_AES_128_GCM_SHA256"));
        DynamicSslContext ctx = new DynamicSslContext(() -> null, placeholder);

        assertThat(ctx.cipherSuites()).containsExactly("TLS_AES_128_GCM_SHA256");
        verify(placeholder).cipherSuites();
    }

    @Test
    @DisplayName("applicationProtocolNegotiator follows the same supplier-first / placeholder-fallback rule")
    @SuppressWarnings("deprecation")
    void applicationProtocolNegotiator_ShouldFollowSupplierFirstRule() {
        ApplicationProtocolNegotiator negotiator = mock(ApplicationProtocolNegotiator.class);
        when(placeholder.applicationProtocolNegotiator()).thenReturn(negotiator);

        DynamicSslContext fromPlaceholder = new DynamicSslContext(() -> null, placeholder);
        assertThat(fromPlaceholder.applicationProtocolNegotiator()).isSameAs(negotiator);

        DynamicSslContext fromReal = new DynamicSslContext(() -> realContext, placeholder);
        assertThat(fromReal.applicationProtocolNegotiator())
                .isSameAs(realContext.applicationProtocolNegotiator());
    }

    @Test
    @DisplayName("sessionContext follows the same supplier-first / placeholder-fallback rule")
    void sessionContext_ShouldFollowSupplierFirstRule() {
        SSLSessionContext stub = mock(SSLSessionContext.class);
        when(placeholder.sessionContext()).thenReturn(stub);

        DynamicSslContext fromPlaceholder = new DynamicSslContext(() -> null, placeholder);
        assertThat(fromPlaceholder.sessionContext()).isSameAs(stub);

        DynamicSslContext fromReal = new DynamicSslContext(() -> realContext, placeholder);
        assertThat(fromReal.sessionContext()).isSameAs(realContext.sessionContext());
    }

    @Test
    @DisplayName("newEngine(alloc) routes to supplier when present and placeholder when null")
    void newEngineWithAlloc_ShouldFollowSupplierFirstRule() {
        SSLEngine stub = mock(SSLEngine.class);
        when(placeholder.newEngine(ByteBufAllocator.DEFAULT)).thenReturn(stub);

        DynamicSslContext fromPlaceholder = new DynamicSslContext(() -> null, placeholder);
        assertThat(fromPlaceholder.newEngine(ByteBufAllocator.DEFAULT)).isSameAs(stub);

        DynamicSslContext fromReal = new DynamicSslContext(() -> realContext, placeholder);
        assertThat(fromReal.newEngine(ByteBufAllocator.DEFAULT)).isNotNull();
    }

    @Test
    @DisplayName("newEngine(alloc, host, port) routes to supplier when present and placeholder when null")
    void newEngineWithHostAndPort_ShouldFollowSupplierFirstRule() {
        SSLEngine stub = mock(SSLEngine.class);
        when(placeholder.newEngine(ByteBufAllocator.DEFAULT, "peer.local", 8443)).thenReturn(stub);

        DynamicSslContext fromPlaceholder = new DynamicSslContext(() -> null, placeholder);
        assertThat(fromPlaceholder.newEngine(ByteBufAllocator.DEFAULT, "peer.local", 8443))
                .isSameAs(stub);

        DynamicSslContext fromReal = new DynamicSslContext(() -> realContext, placeholder);
        assertThat(fromReal.newEngine(ByteBufAllocator.DEFAULT, "peer.local", 8443)).isNotNull();
    }

    @Test
    @DisplayName("runtime cert rotation: switching the supplier value flips delegation on every call")
    void supplier_ShouldBeReReadOnEveryCallToSupportRuntimeRotation() {
        AtomicReference<SslContext> swap = new AtomicReference<>(null);
        Supplier<SslContext> supplier = swap::get;
        when(placeholder.cipherSuites()).thenReturn(List.of("PLACEHOLDER"));

        DynamicSslContext ctx = new DynamicSslContext(supplier, placeholder);
        assertThat(ctx.cipherSuites()).containsExactly("PLACEHOLDER");

        swap.set(realContext);
        assertThat(ctx.cipherSuites()).isEqualTo(realContext.cipherSuites());

        swap.set(null);
        assertThat(ctx.cipherSuites()).containsExactly("PLACEHOLDER");
    }

    @Test
    @DisplayName("constructor rejects a null supplier with NullPointerException")
    void constructor_ShouldRejectNullSupplier() {
        assertThatThrownBy(() -> new DynamicSslContext(null, placeholder))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegateSupplier");
    }

    @Test
    @DisplayName("constructor rejects a null placeholder with NullPointerException")
    void constructor_ShouldRejectNullPlaceholder() {
        assertThatThrownBy(() -> new DynamicSslContext(() -> realContext, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("placeholder");
    }
}
