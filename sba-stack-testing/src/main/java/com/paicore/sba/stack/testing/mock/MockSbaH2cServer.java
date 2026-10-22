package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.codec.SmsTpduCodec;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MoMultipartPayload;
import com.paicore.sba.stack.mo.MultipartRelatedDecoder;
import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.testing.controller.MoController;
import com.paicore.sba.stack.testing.controller.RegistrationController;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.fixtures.JwtFixture;
import com.paicore.sba.stack.testing.service.MoService;
import com.paicore.sba.stack.testing.service.RegistrationService;
import com.paicore.sba.stack.testing.util.HexDump;
import com.paicore.sba.stack.testing.util.TpduInspector;
import com.paicore.sba.stack.utils.SdkConverter;
import com.paicore.sba.stack.validation.SupiValidator;
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
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.util.AsciiString;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Same routes as {@link MockSbaCoreServer} but served by Netty over <b>h2c
 * cleartext HTTP/2</b>. The pipeline accepts both:
 *
 * <ul>
 *   <li>HTTP/1.1 with {@code Upgrade: h2c, HTTP2-Settings: ...} — the path
 *       the JDK {@code HttpClient.Version.HTTP_2} actually uses, and the
 *       reason {@code MockSbaCoreServer} (JDK {@code HttpServer}, HTTP/1.1
 *       only) was forcing the JDK client to fall back to HTTP/1.1 in the
 *       Wireshark trace; and</li>
 *   <li>HTTP/2 prior knowledge — the {@code PRI * HTTP/2.0...} preface that
 *       curl --http2-prior-knowledge sends.</li>
 * </ul>
 *
 * <p>Each accepted stream is dissected into a {@link FullHttpRequest} (via
 * {@link Http2StreamFrameToHttpObjectCodec}+{@link HttpObjectAggregator} for
 * the post-upgrade child channel, and {@link HttpObjectAggregator} for the
 * non-upgraded HTTP/1.1 channel) so the same route handler can drive both
 * protocol versions. Wireshark on the loopback interface will report the
 * second variant as {@code HTTP2} once the upgrade completes.
 */
@Slf4j
public class MockSbaH2cServer implements AutoCloseable {

    private static final String AUTH_HEADER = "authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_CONTENT = 1 << 20;
    private static final String SENDSMS = "/sendsms";
    private static final String UE_CONTEXTS = "/ue-contexts/";
    private static final String CONTENT_TYPE_HDR = "content-type";
    private static final String MOCK_METHOD_NOT_ALLOWED = "MOCK-4050";
    private static final String USE_POST = "Use POST";
    private static final String AUTH_ERROR_CODE = "SMSF-4010";
    private static final String AUTH_ERROR_MSG = "Missing or invalid bearer token";

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup workers = new NioEventLoopGroup();
    private final JwtFixture jwtFixture;
    private final JwtAuthenticator jwtAuthenticator;
    private final SmsTpduCodec codec = new Ss7SmsTpduCodec();
    private final boolean requireAuth;
    @Setter
    private volatile boolean wireDump = false;

    @Getter private final RegistrationService registrationService = new RegistrationService();
    @Getter private final MoService moService = new MoService(registrationService);
    private final RegistrationController registrationController =
            new RegistrationController(registrationService);
    private final MoController moController = new MoController(moService);

    @Getter private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    @Getter private final List<MockSbaCoreServer.MtCapture> mtCaptures = new CopyOnWriteArrayList<>();
    @Getter private final AtomicInteger mtCount = new AtomicInteger();
    @Getter private final AtomicInteger authFailures = new AtomicInteger();

    private final AtomicReference<List<NFProfile>> nfProfiles = new AtomicReference<>(List.of());
    private final AtomicReference<MtDeliveryBehavior> mtBehavior =
            new AtomicReference<>(MtDeliveryBehavior.alwaysDeliver());
    private final SbaSupportRoutes supportRoutes;

    private Channel serverChannel;
    @Getter private volatile int port;

    private MockSbaH2cServer(JwtFixture jwtFixture, boolean requireAuth) {
        this.jwtFixture = jwtFixture;
        this.jwtAuthenticator = jwtFixture.authenticator();
        this.requireAuth = requireAuth;
        this.supportRoutes = new SbaSupportRoutes(nfProfiles, jwtFixture);
    }

    public static MockSbaH2cServer start(String host, int port,
                                         boolean requireAuth, JwtFixture jwt) {
        MockSbaH2cServer server = new MockSbaH2cServer(jwt, requireAuth);
        server.bind(host, port);
        return server;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port);
    }

    public JwtFixture jwt() {
        return jwtFixture;
    }

    public String issueToken(String subject, String... scopes) {
        return jwtFixture.issueToken(subject, Duration.ofMinutes(5), scopes);
    }

    public void setNfProfiles(List<NFProfile> profiles) {
        nfProfiles.set(List.copyOf(profiles));
    }

    public void setMtBehavior(MtDeliveryBehavior behavior) {
        mtBehavior.set(behavior);
    }

    @Override
    public void close() {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            log.info("MockSbaH2cServer stopped");
        }
    }

    private void bind(String host, int requestedPort) {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChildInitializer());
            this.serverChannel = bootstrap.bind(host, requestedPort).sync().channel();
            this.port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
            log.info("MockSbaH2cServer listening on {}:{} (h2c-Upgrade + HTTP/1.1, auth={})",
                    host, port, requireAuth);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start MockSbaH2cServer", e);
        }
    }

    // ------------------------------------------------------------- pipeline

    private class ChildInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            HttpServerCodec sourceCodec = new HttpServerCodec();
            HttpServerUpgradeHandler.UpgradeCodecFactory factory = protocol -> {
                if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                    return new Http2ServerUpgradeCodec(
                            Http2FrameCodecBuilder.forServer().build(),
                            new Http2MultiplexHandler(new H2ChildInitializer()));
                }
                return null;
            };
            HttpServerUpgradeHandler upgradeHandler =
                    new HttpServerUpgradeHandler(sourceCodec, factory, MAX_CONTENT);
            // Prior-knowledge HTTP/2 handler — same h2 codec + multiplex layout
            // used after a successful Upgrade.
            ChannelInitializer<Channel> priorKnowledgeH2 = new ChannelInitializer<>() {
                @Override
                protected void initChannel(Channel inner) {
                    inner.pipeline()
                            .addLast(Http2FrameCodecBuilder.forServer().build())
                            .addLast(new Http2MultiplexHandler(new H2ChildInitializer()));
                }
            };
            // CleartextHttp2ServerUpgradeHandler handles BOTH HTTP/1.1+Upgrade
            // and HTTP/2 prior-knowledge (the "PRI * HTTP/2.0" preface).
            CleartextHttp2ServerUpgradeHandler cleartextUpgrade =
                    new CleartextHttp2ServerUpgradeHandler(
                            sourceCodec, upgradeHandler, priorKnowledgeH2);

            ch.pipeline()
                    .addLast(cleartextUpgrade)
                    .addLast(new HttpObjectAggregator(MAX_CONTENT))
                    .addLast(new Http1RouteHandler());
        }
    }

    /** Per HTTP/2 stream child channel — convert frames back to FullHttpRequest. */
    private class H2ChildInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            ch.pipeline()
                    .addLast(new Http2StreamFrameToHttpObjectCodec(true))
                    .addLast(new HttpObjectAggregator(MAX_CONTENT))
                    .addLast(new Http1RouteHandler());
        }
    }

    /**
     * Same route handler for both HTTP/1.1 and HTTP/2 — both pipelines emit
     * {@link FullHttpRequest}, so the dispatch logic is protocol-agnostic.
     * Owns {@link #route(FullHttpRequest)} so the request-line parsing and
     * dispatch stay co-located with the channel callbacks that drive them.
     */
    private class Http1RouteHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            try {
                FullHttpResponse resp = route(req);
                ctx.writeAndFlush(resp);
            } catch (Exception e) {
                log.warn("Route failure: {}", e.getMessage(), e);
                ctx.writeAndFlush(jsonResponse(500,
                        problem("MOCK-5000", e.getMessage())));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Pipeline error: {}", cause.toString());
            ctx.close();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        /** Catches plain HTTP/1.1 messages that arrive BEFORE upgrade. */
        @Override
        public boolean acceptInboundMessage(Object msg) {
            return msg instanceof HttpMessage;
        }

        private FullHttpResponse route(FullHttpRequest req) {
            String method = req.method().name();
            String uri = req.uri();
            String path = uri;
            String query = "";
            int q = uri.indexOf('?');
            if (q >= 0) {
                path = uri.substring(0, q);
                query = uri.substring(q + 1);
            }
            byte[] body = bytes(req.content());
            Map<String, String> headers = headersToMap(req);

            // Netty's Http2StreamFrameToHttpObjectCodec adds the H2 stream id
            // as a synthetic header on every request it converts from HTTP/2
            // frames. Its presence is wire-level proof that the request
            // arrived over an upgraded HTTP/2 connection rather than plain
            // HTTP/1.1.
            String streamId = headers.get("x-http2-stream-id");
            String wire = streamId != null
                    ? "HTTP/2 stream=" + streamId
                    : "HTTP/1.1";

            log.info("[h2c-mock] {} {} {}  ({} bytes body, content-type={})",
                    wire, method, path, body.length,
                    headers.getOrDefault(CONTENT_TYPE_HDR, "?"));

            try {
                FullHttpResponse resp = doRoute(method, path, query, headers, body);
                recordRequest(method, path, query, headers, body, resp.status().code());
                return resp;
            } catch (Exception e) {
                log.warn("[h2c-mock] route exception: {}", e.getMessage(), e);
                return jsonResponse(500, problem("MOCK-5000", e.getMessage()));
            }
        }
    }

    // ----------------------------------------------------------- routing

    @SuppressWarnings("java:S3398")
    private FullHttpResponse doRoute(String method, String path, String query,
                                     Map<String, String> headers, byte[] body) {
        if (path.startsWith(UE_CONTEXTS)) {
            if (path.endsWith(SENDSMS)) {
                String supi = path.substring(UE_CONTEXTS.length(),
                        path.length() - SENDSMS.length());
                return handleMo(method, supi, headers, body);
            }
            String supi = path.substring(UE_CONTEXTS.length());
            if ("DELETE".equalsIgnoreCase(method)) {
                return handleDeregister(headers, supi);
            }
            return handleRegister(method, supi, headers, body);
        }
        if (path.startsWith("/mock/ue-contexts/") && path.endsWith(SENDSMS)) {
            String supi = path.substring("/mock/ue-contexts/".length(),
                    path.length() - SENDSMS.length());
            return handleMt(method, supi, headers, body);
        }
        if (path.equals(SbaSupportRoutes.NRF_DISCOVERY_PATH)) {
            return toFullHttpResponse(supportRoutes.nrfDiscovery(method, query));
        }
        if (path.equals(SbaSupportRoutes.JWKS_PATH) || path.equals(SbaSupportRoutes.TOKEN_PATH)) {
            return toFullHttpResponse(supportRoutes.oauth2(method, path));
        }
        return jsonResponse(404, problem("MOCK-4040", "Unknown route: " + path));
    }

    private FullHttpResponse handleRegister(String method, String supi,
                                            Map<String, String> headers, byte[] body) {
        if (!"POST".equalsIgnoreCase(method)) {
            return jsonResponse(405, problem(MOCK_METHOD_NOT_ALLOWED, "Use POST to register UE"));
        }
        if (requireAuth && !authenticate(headers)) {
            return jsonResponse(401, problem(AUTH_ERROR_CODE, AUTH_ERROR_MSG));
        }
        return toFullHttpResponse(registrationController.register(supi, body));
    }

    private FullHttpResponse handleDeregister(Map<String, String> headers, String supi) {
        if (requireAuth && !authenticate(headers)) {
            return jsonResponse(401, problem(AUTH_ERROR_CODE, AUTH_ERROR_MSG));
        }
        return toFullHttpResponse(registrationController.deregister(supi));
    }

    private FullHttpResponse handleMo(String method, String supi,
                                      Map<String, String> headers, byte[] body) {
        if (!"POST".equalsIgnoreCase(method)) {
            return jsonResponse(405, problem(MOCK_METHOD_NOT_ALLOWED, USE_POST));
        }
        if (requireAuth && !authenticate(headers)) {
            return jsonResponse(401, problem(AUTH_ERROR_CODE, AUTH_ERROR_MSG));
        }
        HttpResult result = moController.sendsms(supi, body, headers);
        // Wire-dump hook (binary part is parsed inside the controller; for the
        // audit log we still want to see what came over the wire).
        if (wireDump && result.status() / 100 == 2) {
            // We can't easily reach the decoded TPDU bytes from HttpResult, so
            // dump the raw multipart body length for now.
            log.info("[mock<-MO] supi={} multipart body {} B Content-Type: {}",
                    supi, body.length, headers.get(CONTENT_TYPE_HDR));
        }
        return toFullHttpResponse(result);
    }

    private static FullHttpResponse toFullHttpResponse(HttpResult result) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(result.status()),
                result.body() == null || result.body().length == 0
                        ? Unpooled.EMPTY_BUFFER
                        : Unpooled.wrappedBuffer(result.body()));
        result.headers().forEach((k, v) -> resp.headers().add(k, v));
        return resp;
    }

    private FullHttpResponse handleMt(String method, String supi,
                                      Map<String, String> headers, byte[] body) {
        if (!"POST".equalsIgnoreCase(method)) {
            return jsonResponse(405, problem(MOCK_METHOD_NOT_ALLOWED, USE_POST));
        }
        if (requireAuth && !authenticate(headers)) {
            return jsonResponse(401, problem(AUTH_ERROR_CODE, AUTH_ERROR_MSG));
        }
        try {
            SupiValidator.requireValid(supi);
        } catch (SdkException e) {
            return jsonResponse(400, problem(e.getCode(), e.getDetails()));
        }
        String contentType = headers.get(CONTENT_TYPE_HDR);
        MoMultipartPayload payload;
        try {
            payload = MultipartRelatedDecoder.decode(body, contentType);
        } catch (SdkException e) {
            return jsonResponse(400, problem(e.getCode(), e.getDetails()));
        }
        SmsTpdu decoded;
        try {
            decoded = codec.decode(payload.getBinaryData());
        } catch (SdkException e) {
            return jsonResponse(400, problem(e.getCode(), e.getDetails()));
        }
        int count = mtCount.incrementAndGet();
        mtCaptures.add(new MockSbaCoreServer.MtCapture(supi, payload.getJsonData(),
                payload.getBinaryData(), decoded));
        dumpWireReceive(supi, contentType, payload.getBinaryData());
        MtDeliveryBehavior behavior = mtBehavior.get();
        MtDeliveryBehavior.Decision decision = behavior.decide(supi, count);
        if (decision.delay().toMillis() > 0) {
            try { Thread.sleep(decision.delay().toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (decision.deliver()) {
            // Return 200 + JSON DLR body so the response is non-empty and the
            // audit trail shows useful payload in both directions of the MT
            // flow. 3GPP TS29.540 allows 200/202/204 here; 204 is shorter but
            // hides the receiver's confirmation under a wireshark filter.
            Map<String, Object> dlr = new LinkedHashMap<>();
            dlr.put("smsRecordId", payload.getJsonData().getSmsRecordId());
            dlr.put("status", "DELIVERED");
            dlr.put("dcs", decoded.getSmsTpduType().name());
            dlr.put("tpduLength", payload.getBinaryData() == null ? 0 : payload.getBinaryData().length);
            dlr.put("deliveryTimestamp", Instant.now().toString());
            return jsonResponse(200, dlr);
        }
        return jsonResponse(decision.statusCode(),
                problem(decision.errorCode(), decision.errorDetail()));
    }

    // ----------------------------------------------------------- helpers

    private boolean authenticate(Map<String, String> headers) {
        String header = headers.get(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            authFailures.incrementAndGet();
            return false;
        }
        try {
            jwtAuthenticator.authenticate(header.substring(BEARER_PREFIX.length()).trim());
            return true;
        } catch (AuthenticationException e) {
            authFailures.incrementAndGet();
            return false;
        }
    }

    private void dumpWireReceive(String supi, String contentType, byte[] tpdu) {
        if (!wireDump) {
            return;
        }
        log.info("[mock<-{}] supi={} Content-Type: {}", "MT", supi, contentType);
        log.info("[mock<-{}] TPDU received ({} bytes):\n{}", "MT",
                tpdu == null ? 0 : tpdu.length, HexDump.format(tpdu));
        log.info("[mock<-{}] jss7 decode: {}", "MT", TpduInspector.inspect(tpdu));
    }

    @SuppressWarnings("java:S3398")
    private void recordRequest(String method, String path, String query,
                               Map<String, String> headers, byte[] body, int status) {
        recorded.add(RecordedRequest.builder()
                .method(method).path(path).query(query)
                .headers(headers).body(body)
                .receivedAt(Instant.now())
                .responseStatus(status)
                .build());
    }

    private static Map<String, String> headersToMap(FullHttpRequest req) {
        Map<String, String> map = new HashMap<>();
        req.headers().forEach(e -> map.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
        return map;
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] arr = new byte[buf.readableBytes()];
        buf.readBytes(arr);
        return arr;
    }


    private static Map<String, Object> problem(String code, String detail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "https://sba-stack.local/errors/" + code);
        p.put("title", code);
        p.put("detail", detail == null ? "" : detail);
        return p;
    }

    private static FullHttpResponse jsonResponse(int status, Object body) {
        byte[] bytes = SdkConverter.valueAsString(body).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        resp.headers()
                .add(CONTENT_TYPE_HDR, status >= 400 ? "application/problem+json" : "application/json")
                .add("content-length", String.valueOf(bytes.length));
        return resp;
    }

}
