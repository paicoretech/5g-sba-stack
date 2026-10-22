package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MoMultipartPayload;
import com.paicore.sba.stack.mo.MultipartRelatedDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cleartext HTTP/2 mock server. The pipeline accepts both:
 *
 * <ul>
 *   <li>HTTP/1.1 with {@code Upgrade: h2c, HTTP2-Settings: ...} — the path the
 *       JDK {@code HttpClient.Version.HTTP_2} actually uses; the first request
 *       is converted by {@link Http2ServerUpgradeCodec} into HTTP/2 stream 1.</li>
 *   <li>HTTP/2 prior knowledge — the {@code PRI * HTTP/2.0...} preface that
 *       {@code curl --http2-prior-knowledge} sends.</li>
 * </ul>
 *
 * <p>{@link #startTls(SslContext)} serves the same endpoint over TLS with ALPN, so a caller can
 * exercise a real handshake — including mutual TLS, by passing a context built with
 * {@code clientAuth(ClientAuth.REQUIRE)} and a truststore. The certificates are the caller's
 * concern; this class only needs the finished context.</p>
 *
 * <p>The server intentionally only implements one endpoint —
 * {@code POST /nsmsf-sms/v2/ue-contexts/{supi}/sendsms} — because that is the
 * path {@link com.paicore.sba.stack.transport.http2.Http2RequestDispatcher}
 * uses for MT dispatch. The body is parsed as multipart/related and the TPDU
 * is decoded, so a successful 204 means every layer of the stack interoperated
 * correctly.
 */
@Slf4j
public class Http2MockServer implements AutoCloseable {

    private static final int MAX_CONTENT = 1024 * 1024;
    private static final String CONTENT_TYPE = "content-type";

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup workers = new NioEventLoopGroup();
    private final ExecutorService handlerPool;
    private final AtomicInteger streamCount = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();
    private final Ss7SmsTpduCodec codec = new Ss7SmsTpduCodec();

    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();
    private final AtomicInteger port = new AtomicInteger();

    /** Null for cleartext. When set, the pipeline terminates TLS and negotiates h2 over ALPN. */
    private final SslContext sslContext;

    private Http2MockServer(SslContext sslContext) {
        this.sslContext = sslContext;
        this.handlerPool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("h2-mock-", 0).factory());
    }

    public static Http2MockServer startCleartext() {
        Http2MockServer server = new Http2MockServer(null);
        server.start();
        return server;
    }

    /**
     * Serves HTTP/2 over TLS, negotiated with ALPN.
     *
     * @param sslContext a server context whose ALPN advertises {@code h2}. Require a client
     *                   certificate on it to make this a mutual-TLS endpoint.
     */
    public static Http2MockServer startTls(SslContext sslContext) {
        return startTls(sslContext, 0);
    }

    /**
     * Same as {@link #startTls(SslContext)} on a fixed port, for callers that keep their test
     * traffic inside a known range so a packet capture can be told which ports carry HTTP/2.
     *
     * @param bindPort the port to bind, or {@code 0} to let the OS choose
     */
    public static Http2MockServer startTls(SslContext sslContext, int bindPort) {
        Http2MockServer server = new Http2MockServer(sslContext);
        server.start(bindPort);
        return server;
    }

    /** Whether this instance terminates TLS. */
    public boolean isTls() { return sslContext != null; }

    public int port() { return port.get(); }
    public int streamCount() { return streamCount.get(); }
    public int requestCount() { return requestCount.get(); }
    public int errorCount() { return errorCount.get(); }
    public java.util.List<Capture> captures() { return captures; }

    private void start() {
        start(0);
    }

    private void start(int bindPort) {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChildInitializer());
            Channel bound = bootstrap.bind("127.0.0.1", bindPort).sync().channel();
            serverChannel.set(bound);
            port.set(((java.net.InetSocketAddress) bound.localAddress()).getPort());
            log.info("Http2MockServer ({}) listening on 127.0.0.1:{}",
                    isTls() ? "TLS + ALPN h2" : "h2c-Upgrade + prior-knowledge", port.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Http2MockServer", e);
        }
    }

    @Override
    public void close() {
        try {
            Channel ch = serverChannel.getAndSet(null);
            if (ch != null) {
                ch.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            handlerPool.shutdown();
            log.info("Http2MockServer stopped");
        }
    }

    private class ChildInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            if (isTls()) {
                initTls(ch);
                return;
            }
            HttpServerCodec sourceCodec = new HttpServerCodec();
            HttpServerUpgradeHandler.UpgradeCodecFactory factory = protocol -> {
                if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                    return new Http2ServerUpgradeCodec(
                            Http2FrameCodecBuilder.forServer().build(),
                            new Http2MultiplexHandler(new H2StreamInitializer()));
                }
                return null;
            };
            HttpServerUpgradeHandler upgradeHandler =
                    new HttpServerUpgradeHandler(sourceCodec, factory, MAX_CONTENT);
            ChannelInitializer<Channel> priorKnowledgeH2 = new ChannelInitializer<>() {
                @Override
                protected void initChannel(Channel inner) {
                    inner.pipeline()
                            .addLast(Http2FrameCodecBuilder.forServer().build())
                            .addLast(new Http2MultiplexHandler(new H2StreamInitializer()));
                }
            };
            CleartextHttp2ServerUpgradeHandler cleartextUpgrade =
                    new CleartextHttp2ServerUpgradeHandler(
                            sourceCodec, upgradeHandler, priorKnowledgeH2);

            ch.pipeline()
                    .addLast(cleartextUpgrade)
                    .addLast(new HttpObjectAggregator(MAX_CONTENT))
                    .addLast(new Http1FallbackHandler());
        }

        /**
         * TLS first, then ALPN decides. There is no h2c upgrade path here: over TLS the protocol is
         * settled during the handshake, so a client that fails to negotiate h2 is closed rather
         * than silently served HTTP/1.1.
         */
        private void initTls(SocketChannel ch) {
            ch.pipeline()
                    .addLast(sslContext.newHandler(ch.alloc()))
                    .addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                errorCount.incrementAndGet();
                                log.warn("Http2MockServer: client negotiated {} instead of h2, closing", protocol);
                                ctx.close();
                                return;
                            }
                            ctx.pipeline()
                                    .addLast(Http2FrameCodecBuilder.forServer().build())
                                    .addLast(new Http2MultiplexHandler(new H2StreamInitializer()));
                        }
                    });
        }
    }

    private class H2StreamInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            streamCount.incrementAndGet();
            ch.pipeline().addLast(new StreamHandler());
        }
    }

    /**
     * Aggregates the headers + data frames of a single HTTP/2 stream and
     * dispatches the assembled request to {@link #handle}.
     */
    private class StreamHandler extends SimpleChannelInboundHandler<Http2StreamFrame> {

        private Http2Headers requestHeaders;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame frame) {
            if (frame instanceof Http2HeadersFrame headers) {
                this.requestHeaders = headers.headers();
                if (headers.isEndStream()) {
                    dispatch(ctx);
                }
            } else if (frame instanceof Http2DataFrame data) {
                ByteBuf content = data.content();
                int readable = content.readableBytes();
                byte[] chunk = new byte[readable];
                content.readBytes(chunk);
                body.write(chunk, 0, chunk.length);
                if (data.isEndStream()) {
                    dispatch(ctx);
                }
            }
        }

        private void dispatch(ChannelHandlerContext ctx) {
            Http2Headers headers = this.requestHeaders;
            byte[] payload = body.toByteArray();
            handlerPool.execute(() -> {
                try {
                    handle(ctx, headers, payload);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.warn("Http2MockServer handler failure: {}", e.getMessage(), e);
                    respond(ctx, 500, ("{\"error\":\"" + e.getMessage() + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                }
            });
        }

        private void handle(ChannelHandlerContext ctx, Http2Headers headers, byte[] payload) {
            requestCount.incrementAndGet();
            String method = headers.method() == null ? "" : headers.method().toString();
            String path = headers.path() == null ? "" : headers.path().toString();
            String contentType = headers.get(CONTENT_TYPE) == null
                    ? null : headers.get(CONTENT_TYPE).toString();

            if (!method.equalsIgnoreCase("POST") || !path.contains("/sendsms")) {
                respond(ctx, 404, "{\"error\":\"unknown route\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                MoMultipartPayload decoded = MultipartRelatedDecoder.decode(payload, contentType);
                var tpdu = codec.decode(decoded.getBinaryData());
                captures.add(new Capture(path, decoded.getJsonData().getSmsRecordId(),
                        tpdu.getSmsTpduType().name(), payload.length));
                respond(ctx, 204, new byte[0]);
            } catch (SdkException e) {
                errorCount.incrementAndGet();
                respond(ctx, 400, ("{\"code\":\"" + e.getCode() + "\"}").getBytes(StandardCharsets.UTF_8));
            }
        }

        private void respond(ChannelHandlerContext ctx, int status, byte[] responseBody) {
            Http2Headers headers = new DefaultHttp2Headers()
                    .status(String.valueOf(status))
                    .add(CONTENT_TYPE, "application/json")
                    .add("content-length", String.valueOf(responseBody.length))
                    .add("x-mock-server", "sba-stack-testing");
            DefaultHttp2HeadersFrame headersFrame =
                    new DefaultHttp2HeadersFrame(headers, responseBody.length == 0);
            ctx.write(headersFrame);
            if (responseBody.length > 0) {
                ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(responseBody), true));
            }
            ctx.flush();
        }
    }

    /**
     * Handles HTTP/1.1 messages that arrive without an Upgrade — returns 426 so
     * clients fall back to a real HTTP/2 negotiation. Most clients (JDK
     * HttpClient HTTP_2) will send Upgrade and never hit this branch.
     */
    private static class Http1FallbackHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.UPGRADE_REQUIRED);
            resp.headers().set("upgrade", "h2c");
            resp.headers().set("content-length", "0");
            ctx.writeAndFlush(resp);
        }

        @Override
        public boolean acceptInboundMessage(Object msg) {
            return msg instanceof HttpMessage;
        }
    }

    /** Per-stream snapshot — useful for asserting stream concurrency / reuse. */
    public record Capture(String path, String smsRecordId, String tpduType, int bodyLength) { }
}
