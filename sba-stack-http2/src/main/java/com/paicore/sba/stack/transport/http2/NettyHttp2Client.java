package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import com.paicore.sba.stack.transport.http2.dto.StreamContext;
import com.paicore.sba.stack.transport.http2.util.Http2Constants;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBridge;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class NettyHttp2Client implements Http2Client {

    private final Http2ClientSettings settings;
    private final EventLoopGroup workerGroup;
    private final boolean ownsGroup;
    private final AtomicReference<Http2StreamHandler> streamHandlerRef = new AtomicReference<>();

    private final AtomicReference<Channel> channelRef = new AtomicReference<>();

    // S5993: fail-fast validation in the constructor is intentional — settings
    // are immutable, no resources are acquired before the throw, so there is no
    // partially-initialised instance to worry about.
    @SuppressWarnings("java:S5993")
    public NettyHttp2Client(Http2ClientSettings settings) {
        this(requireValid(settings), new NioEventLoopGroup(), true);
    }

    @SuppressWarnings("java:S5993")
    NettyHttp2Client(Http2ClientSettings settings, EventLoopGroup workerGroup, boolean ownsGroup) {
        this.settings = requireValid(settings);
        this.workerGroup = workerGroup;
        this.ownsGroup = ownsGroup;
    }

    private static Http2ClientSettings requireValid(Http2ClientSettings settings) {
        if (settings == null || settings.getHost() == null || settings.getHost().isBlank()) {
            throw new Http2TransportException("Http2ClientSettings.host is required");
        }
        return settings;
    }

    @Override
    public void connect() {
        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getPool().getConnectTimeoutMillis())
                .handler(new Http2ClientChannelInitializer(settings, streamHandlerRef::set));
        try {
            channelRef.set(bootstrap.connect(settings.getHost(), settings.getPort()).sync().channel());
            log.info("HTTP/2 client connected to {}:{}", settings.getHost(), settings.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Http2TransportException("Interrupted while connecting to "
                    + settings.getHost() + ":" + settings.getPort(), e);
        }
    }

    @Override
    public boolean isConnected() {
        Channel ch = channelRef.get();
        return ch != null && ch.isActive();
    }

    @Override
    public CompletableFuture<Http2Response> send(Http2Request request) {
        Channel ch = channelRef.get();
        if (Objects.isNull(ch) || !ch.isActive()) {
            CompletableFuture<Http2Response> failed = new CompletableFuture<>();
            failed.completeExceptionally(new Http2TransportException("Client is not connected"));
            return failed;
        }
        Http2StreamHandler streamHandler = streamHandlerRef.get();
        if (Objects.isNull(streamHandler)) {
            CompletableFuture<Http2Response> failed = new CompletableFuture<>();
            failed.completeExceptionally(new Http2TransportException("Stream handler is not bound to the channel"));
            return failed;
        }
        Http2FrameStream stream = newStream(ch);
        String correlationId = Objects.nonNull(request.getCorrelationId())
                ? request.getCorrelationId() : UUID.randomUUID().toString();
        StreamContext ctx = new StreamContext(stream, correlationId);
        streamHandler.register(ctx);

        Http2Headers headers = buildHeaders(request);
        DefaultHttp2HeadersFrame headersFrame =
                new DefaultHttp2HeadersFrame(headers, Objects.isNull(request.getBody()));
        headersFrame.stream(stream);
        ch.write(headersFrame);
        if (Objects.nonNull(request.getBody())) {
            DefaultHttp2DataFrame dataFrame =
                    new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(request.getBody()), true);
            dataFrame.stream(stream);
            ch.write(dataFrame);
        }
        ch.flush();
        return ctx.getFuture();
    }

    private Http2FrameStream newStream(Channel ch) {
        Http2FrameCodec codec = ch.pipeline().get(Http2FrameCodec.class);
        if (Objects.nonNull(codec)) {
            return Http2FrameCodecBridge.newStream(codec);
        }
        return new EmbeddedStream();
    }

    private Http2Headers buildHeaders(Http2Request request) {
        boolean tlsOn = Objects.nonNull(settings.getTls()) && settings.getTls().isEnabled();
        Http2Headers headers = new DefaultHttp2Headers()
                .method(request.getMethod() == null ? "POST" : request.getMethod())
                .scheme(tlsOn ? Http2Constants.SCHEME_HTTPS : "http")
                .path(request.getPath() == null ? "/" : request.getPath())
                .authority(request.getAuthority() != null
                        ? request.getAuthority()
                        : settings.getHost() + ":" + settings.getPort());
        headers.add(Http2Constants.HEADER_USER_AGENT, settings.getUserAgent());
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> e : request.getHeaders().entrySet()) {
                headers.add(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        if (request.getBody() != null) {
            headers.add(Http2Constants.HEADER_CONTENT_LENGTH, String.valueOf(request.getBody().length));
        }
        return headers;
    }

    @Override
    public void close() {
        try {
            Channel ch = channelRef.getAndSet(null);
            if (ch != null) {
                ch.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (ownsGroup) {
                workerGroup.shutdownGracefully();
            }
            log.info("HTTP/2 client closed");
        }
    }

    Http2StreamHandler streamHandler() {
        return streamHandlerRef.get();
    }

    private static final class EmbeddedStream implements Http2FrameStream {
        private static final java.util.concurrent.atomic.AtomicInteger ID_GEN =
                new java.util.concurrent.atomic.AtomicInteger(3);
        private final int id = ID_GEN.getAndAdd(2);

        @Override
        public int id() {
            return id;
        }

        @Override
        public io.netty.handler.codec.http2.Http2Stream.State state() {
            return io.netty.handler.codec.http2.Http2Stream.State.OPEN;
        }
    }
}
