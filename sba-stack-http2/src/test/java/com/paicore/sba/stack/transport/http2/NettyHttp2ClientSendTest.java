package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyHttp2ClientSendTest {

    private static Http2ClientSettings basicSettings() {
        return Http2ClientSettings.builder()
                .host("amf.example.com")
                .port(8443)
                .tls(TlsSettings.builder().enabled(false).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static void setChannel(NettyHttp2Client client, Channel channel) throws Exception {
        Field f = NettyHttp2Client.class.getDeclaredField("channelRef");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<Channel>) f.get(client)).set(channel);
    }

    @SuppressWarnings("unchecked")
    private static Http2StreamHandler bindStreamHandler(NettyHttp2Client client) throws Exception {
        Http2StreamHandler handler = new Http2StreamHandler();
        Field f = NettyHttp2Client.class.getDeclaredField("streamHandlerRef");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<Http2StreamHandler>) f.get(client)).set(handler);
        return handler;
    }

    @Test
    void constructorRejectsBlankHost() {
        Http2ClientSettings settings = Http2ClientSettings.builder().host("   ").build();
        assertThatThrownBy(() -> new NettyHttp2Client(settings))
                .isInstanceOf(Http2TransportException.class);
    }

    @Test
    void constructorRejectsNullSettings() {
        assertThatThrownBy(() -> new NettyHttp2Client(null))
                .isInstanceOf(Http2TransportException.class);
    }

    @Test
    void isConnectedFalseWhenChannelNull() {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        try (NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, false)) {
            assertThat(client.isConnected()).isFalse();
        } finally {
            grp.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void sendIssuesHeadersAndBodyFramesWhenConnected() throws Exception {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, false);
        try {
            EmbeddedChannel embedded = new EmbeddedChannel();
            Http2StreamHandler handler = bindStreamHandler(client);
            embedded.pipeline().addLast(handler);
            setChannel(client, embedded);

            CompletableFuture<Http2Response> future = client.send(Http2Request.builder()
                    .method("POST")
                    .path("/nsmsf-sms/v2/ue-contexts/imsi-1/sendsms")
                    .authority("amf:8443")
                    .correlationId("c-1")
                    .headers(Map.of("Trace-Id", "abc-123"))
                    .body("payload".getBytes())
                    .build());

            embedded.flushOutbound();

            Object headers = embedded.readOutbound();
            Object data = embedded.readOutbound();
            assertThat(headers).isInstanceOf(Http2HeadersFrame.class);
            assertThat(data).isInstanceOf(Http2DataFrame.class);

            Http2HeadersFrame h = (Http2HeadersFrame) headers;
            assertThat(h.headers().path())
                    .hasToString("/nsmsf-sms/v2/ue-contexts/imsi-1/sendsms");
            assertThat(h.headers().method()).hasToString("POST");
            assertThat(h.headers().get("trace-id")).hasToString("abc-123");
            assertThat(h.headers().get("content-length")).hasToString("7");

            // Simulate server response
            DefaultHttp2HeadersFrame respHeaders = new DefaultHttp2HeadersFrame(
                    new DefaultHttp2Headers().status("202"), false);
            respHeaders.stream(h.stream());
            embedded.writeInbound(respHeaders);
            DefaultHttp2DataFrame respData = new DefaultHttp2DataFrame(
                    io.netty.buffer.Unpooled.copiedBuffer("OK", java.nio.charset.StandardCharsets.UTF_8),
                    true);
            respData.stream(h.stream());
            embedded.writeInbound(respData);

            Http2Response response = future.get(1, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(new String(response.getBody())).isEqualTo("OK");
            assertThat(response.getCorrelationId()).isEqualTo("c-1");

            embedded.finishAndReleaseAll();
        } finally {
            grp.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void sendUsesDefaultMethodAndPathWhenAbsent() throws Exception {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, false);
        try {
            EmbeddedChannel embedded = new EmbeddedChannel();
            embedded.pipeline().addLast(bindStreamHandler(client));
            setChannel(client, embedded);

            client.send(Http2Request.builder().build());
            embedded.flushOutbound();

            Http2HeadersFrame headers = embedded.readOutbound();
            assertThat(headers.headers().method()).hasToString("POST");
            assertThat(headers.headers().path()).hasToString("/");
            assertThat(headers.headers().authority()).hasToString("amf.example.com:8443");
            // No body => endStream on headers frame
            assertThat(headers.isEndStream()).isTrue();

            embedded.finishAndReleaseAll();
        } finally {
            grp.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void sendGeneratesCorrelationIdWhenMissing() throws Exception {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, false);
        try {
            EmbeddedChannel embedded = new EmbeddedChannel();
            embedded.pipeline().addLast(bindStreamHandler(client));
            setChannel(client, embedded);

            CompletableFuture<Http2Response> f1 = client.send(Http2Request.builder().build());
            CompletableFuture<Http2Response> f2 = client.send(Http2Request.builder().build());
            embedded.flushOutbound();

            // Different stream-ids and futures, each generated UUID by client
            assertThat(f1).isNotSameAs(f2);
            assertThat(client.streamHandler().activeStreams()).isEqualTo(2);

            embedded.finishAndReleaseAll();
        } finally {
            grp.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeShutsDownGroupWhenOwned() throws Exception {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        setChannel(client, embedded);

        client.close();

        assertThat(embedded.isOpen()).isFalse();
        assertThat(grp.isShuttingDown() || grp.isShutdown()).isTrue();
    }

    @Test
    void closeWithoutOpenChannelDoesNotThrow() {
        NioEventLoopGroup grp = new NioEventLoopGroup(1);
        try (NettyHttp2Client client = new NettyHttp2Client(basicSettings(), grp, false)) {
            // No channel set
            client.close();
            assertThat(client.isConnected()).isFalse();
        }
        grp.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
    }
}
