package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class Http2ClientChannelInitializerTest {

    private static final Consumer<Http2StreamHandler> NOOP_SINK = h -> {};

    private static Http2ClientSettings settings(TlsSettings tls) {
        return Http2ClientSettings.builder()
                .host("amf.example.com")
                .port(8443)
                .tls(tls)
                .build();
    }

    @Test
    void buildsPipelineWithoutSslWhenTlsDisabled() {
        Http2ClientSettings cfg = settings(TlsSettings.builder().enabled(false).build());
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(cfg, NOOP_SINK);

        EmbeddedChannel channel = new EmbeddedChannel();
        initializer.initChannel(channel);

        assertThat(channel.pipeline().get(SslHandler.class)).isNull();
        assertThat(channel.pipeline().get(Http2FrameCodec.class)).isNotNull();
        assertThat(channel.pipeline().get(Http2StreamHandler.class)).isNotNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void buildsPipelineWithSslWhenTlsEnabled() {
        TlsSettings tls = TlsSettings.builder()
                .enabled(true)
                .verifyHostname(false)
                .protocols(List.of("TLSv1.3"))
                .build();
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(settings(tls), NOOP_SINK);

        EmbeddedChannel channel = new EmbeddedChannel();
        initializer.initChannel(channel);

        assertThat(channel.pipeline().get(SslHandler.class)).isNotNull();
        assertThat(channel.pipeline().get(Http2FrameCodec.class)).isNotNull();
        assertThat(channel.pipeline().get(Http2StreamHandler.class)).isNotNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void initChannelPublishesFreshStreamHandlerToSinkOnEveryCall() {
        Http2ClientSettings cfg = settings(TlsSettings.builder().enabled(false).build());
        AtomicReference<Http2StreamHandler> captured = new AtomicReference<>();
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(cfg, captured::set);

        EmbeddedChannel firstChannel = new EmbeddedChannel();
        initializer.initChannel(firstChannel);
        Http2StreamHandler firstHandler = captured.get();
        assertThat(firstHandler).isNotNull();

        EmbeddedChannel secondChannel = new EmbeddedChannel();
        initializer.initChannel(secondChannel);
        Http2StreamHandler secondHandler = captured.get();
        assertThat(secondHandler).isNotSameAs(firstHandler);

        firstChannel.finishAndReleaseAll();
        secondChannel.finishAndReleaseAll();
    }

    @Test
    void initChannelToleratesNullSink() {
        Http2ClientSettings cfg = settings(TlsSettings.builder().enabled(false).build());
        Http2ClientChannelInitializer initializer = new Http2ClientChannelInitializer(cfg, null);

        EmbeddedChannel channel = new EmbeddedChannel();
        initializer.initChannel(channel);

        assertThat(channel.pipeline().get(Http2StreamHandler.class)).isNotNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void buildSslContextRespectsVerifyHostnameFlag() {
        TlsSettings insecure = TlsSettings.builder()
                .enabled(true)
                .verifyHostname(false)
                .protocols(List.of("TLSv1.3"))
                .build();
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(settings(insecure), NOOP_SINK);

        SslContext ctx = initializer.buildSslContext(insecure);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isClient()).isTrue();
    }

    @Test
    void buildSslContextHandlesEmptyProtocolsList() {
        TlsSettings empty = TlsSettings.builder()
                .enabled(true)
                .verifyHostname(true)
                .protocols(List.of())
                .build();
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(settings(empty), NOOP_SINK);

        SslContext ctx = initializer.buildSslContext(empty);

        assertThat(ctx).isNotNull();
    }

    @Test
    void buildSslContextHandlesNullProtocolsList() {
        TlsSettings nullProtocols = TlsSettings.builder()
                .enabled(true)
                .verifyHostname(true)
                .protocols(null)
                .build();
        Http2ClientChannelInitializer initializer =
                new Http2ClientChannelInitializer(settings(nullProtocols), NOOP_SINK);

        SslContext ctx = initializer.buildSslContext(nullProtocols);

        assertThat(ctx).isNotNull();
    }
}
