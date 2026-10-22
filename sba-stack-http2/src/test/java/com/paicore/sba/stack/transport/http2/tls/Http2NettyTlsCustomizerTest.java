package com.paicore.sba.stack.transport.http2.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http2NettyTlsCustomizerTest {

    private static SslContext placeholder;

    @BeforeAll
    static void mintPlaceholderContext() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("placeholder-ctx");
        placeholder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();
    }

    private CapturingFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CapturingFactory();
    }

    @Test
    @DisplayName("customize registers exactly one server customizer on the factory")
    void customize_ShouldRegisterOneServerCustomizerOnTheFactory() {
        Http2NettyTlsCustomizer customizer = new Http2NettyTlsCustomizer(
                placeholder, () -> placeholder, 5_000L);

        customizer.customize(factory);

        assertThat(factory.captured).hasSize(1);
    }

    @Test
    @DisplayName("registered customizer enables HTTP/2 + HTTP/1.1 ALPN and applies TLS with handshake timeout")
    void registeredCustomizer_ShouldConfigureProtocolAndSecure() {
        Supplier<SslContext> supplier = () -> placeholder;
        Http2NettyTlsCustomizer customizer = new Http2NettyTlsCustomizer(placeholder, supplier, 7_500L);

        customizer.customize(factory);

        assertThat(factory.captured).hasSize(1);
        HttpServer initial = HttpServer.create();
        HttpServer configured = factory.captured.get(0).apply(initial);
        assertThat(configured)
                .as("customizer must return a fully configured HttpServer")
                .isNotNull()
                .isNotSameAs(initial);
        assertThat(configured.configuration().protocols())
                .contains(HttpProtocol.H2, HttpProtocol.HTTP11);
        assertThat(configured.configuration().isSecure()).isTrue();
    }

    @Test
    @DisplayName("constructor rejects a null placeholder context")
    void constructor_ShouldRejectNullPlaceholder() {
        assertThatThrownBy(() -> new Http2NettyTlsCustomizer(null, () -> placeholder, 1_000L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("placeholderContext");
    }

    @Test
    @DisplayName("constructor rejects a null supplier")
    void constructor_ShouldRejectNullSupplier() {
        assertThatThrownBy(() -> new Http2NettyTlsCustomizer(placeholder, null, 1_000L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sslContextSupplier");
    }

    @Test
    @DisplayName("customize tolerates a zero handshake timeout")
    void customize_ShouldAcceptZeroHandshakeTimeout() {
        Http2NettyTlsCustomizer customizer = new Http2NettyTlsCustomizer(
                placeholder, () -> placeholder, 0L);

        customizer.customize(factory);

        assertThat(factory.captured).hasSize(1);
    }

    @Test
    @DisplayName("registered customizer advertises the configured max concurrent streams")
    void registeredCustomizer_ShouldAdvertiseConfiguredMaxConcurrentStreams() {
        Http2NettyTlsCustomizer customizer = new Http2NettyTlsCustomizer(
                placeholder, () -> placeholder, 5_000L, 512L);

        customizer.customize(factory);

        HttpServer configured = factory.captured.get(0).apply(HttpServer.create());
        assertThat(configured.configuration().http2SettingsSpec()).isNotNull();
        assertThat(configured.configuration().http2SettingsSpec().maxConcurrentStreams())
                .isEqualTo(512L);
    }

    @Test
    @DisplayName("the three-argument constructor advertises the default max concurrent streams")
    void registeredCustomizer_ShouldAdvertiseDefaultMaxConcurrentStreams() {
        Http2NettyTlsCustomizer customizer = new Http2NettyTlsCustomizer(
                placeholder, () -> placeholder, 5_000L);

        customizer.customize(factory);

        HttpServer configured = factory.captured.get(0).apply(HttpServer.create());
        assertThat(configured.configuration().http2SettingsSpec()).isNotNull();
        assertThat(configured.configuration().http2SettingsSpec().maxConcurrentStreams())
                .isEqualTo(256L);
    }

    private static final class CapturingFactory extends NettyReactiveWebServerFactory {
        private final List<NettyServerCustomizer> captured = new ArrayList<>();

        @Override
        public void addServerCustomizers(NettyServerCustomizer... customizers) {
            for (NettyServerCustomizer c : customizers) {
                captured.add(c);
            }
        }
    }
}
