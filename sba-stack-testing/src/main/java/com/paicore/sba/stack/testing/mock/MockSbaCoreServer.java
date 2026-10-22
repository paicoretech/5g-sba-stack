package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.codec.SmsTpduCodec;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MoMultipartPayload;
import com.paicore.sba.stack.mo.MultipartRelatedDecoder;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.testing.fixtures.JwtFixture;
import com.paicore.sba.stack.testing.util.HexDump;
import com.paicore.sba.stack.testing.util.TpduInspector;
import com.paicore.sba.stack.utils.SdkConverter;
import com.paicore.sba.stack.validation.SupiValidator;
import com.paicore.sba.stack.validation.UeContextValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process mock of a 5G SBA control plane built on the JDK HTTP server.
 * It hosts all endpoints required by {@code CLAUDE-Testing.md}:
 *
 * <ul>
 *   <li>{@code POST /ue-contexts/{supi}} — registration (Nsmsf_SMService UE context)</li>
 *   <li>{@code POST /ue-contexts/{supi}/sendsms} — MO multipart sendsms</li>
 *   <li>{@code POST /mock/ue-contexts/{supi}/sendsms} — MT delivery receiver</li>
 *   <li>{@code GET  /nnrf-disc/v1/nf-instances} — NRF discovery</li>
 *   <li>{@code PUT  /nnrf-nfm/v1/nf-instances/{nfInstanceId}} — NRF registration</li>
 *   <li>{@code GET  /oauth2/jwks} — JWKS publication</li>
 *   <li>{@code POST /oauth2/token} — client_credentials token endpoint</li>
 * </ul>
 *
 * Requests run on a virtual-thread executor so concurrent MO/MT/registration
 * traffic exercises the same thread-pool model recommended for the production
 * stack (Java 21 virtual threads, see CLAUDE-Testing.md).
 */
@Slf4j
public class MockSbaCoreServer implements AutoCloseable {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String SENDSMS = "/sendsms";
    private static final String UE_CONTEXTS = "/ue-contexts/";
    private static final String CONTENT_TYPE_HDR = "Content-Type";
    private static final String MOCK_METHOD_NOT_ALLOWED = "MOCK-4050";
    private static final String MOCK_NOT_FOUND = "MOCK-4040";

    private final HttpServer server;
    private final ExecutorService executor;
    private final JwtFixture jwtFixture;
    private final JwtAuthenticator jwtAuthenticator;
    private final SmsTpduCodec codec;
    private final boolean requireAuth;
    private volatile boolean wireDump = false;

    @Getter private final ConcurrentMap<String, RegisteredUe> registry = new ConcurrentHashMap<>();
    @Getter private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    @Getter private final List<MoCapture> moCaptures = new CopyOnWriteArrayList<>();
    @Getter private final List<MtCapture> mtCaptures = new CopyOnWriteArrayList<>();
    @Getter private final AtomicInteger registrationCount = new AtomicInteger();
    @Getter private final AtomicInteger moCount = new AtomicInteger();
    @Getter private final AtomicInteger mtCount = new AtomicInteger();
    @Getter private final AtomicInteger authFailures = new AtomicInteger();

    private final AtomicReference<List<NFProfile>> nfProfiles = new AtomicReference<>(List.of());
    private final AtomicReference<MtDeliveryBehavior> mtBehavior =
            new AtomicReference<>(MtDeliveryBehavior.alwaysDeliver());
    private final SbaSupportRoutes supportRoutes;

    private MockSbaCoreServer(HttpServer server, ExecutorService executor,
                              JwtFixture jwtFixture, boolean requireAuth) {
        this.server = server;
        this.executor = executor;
        this.jwtFixture = jwtFixture;
        this.jwtAuthenticator = jwtFixture.authenticator();
        this.codec = new Ss7SmsTpduCodec();
        this.requireAuth = requireAuth;
        this.supportRoutes = new SbaSupportRoutes(nfProfiles, jwtFixture);
    }

    public static MockSbaCoreServer startWithDefaults() {
        return start(LOCALHOST, 0, true, JwtFixture.shared());
    }

    public static MockSbaCoreServer startWithoutAuth() {
        return start(LOCALHOST, 0, false, JwtFixture.shared());
    }

    public static MockSbaCoreServer start(boolean requireAuth, JwtFixture jwt) {
        return start(LOCALHOST, 0, requireAuth, jwt);
    }

    /**
     * Bind to an explicit host + port — use {@code 0.0.0.0} so the socket is
     * reachable from the LAN (and from a Wireshark capture on a non-loopback
     * interface). A port of {@code 0} picks a free ephemeral one.
     */
    public static MockSbaCoreServer start(String host, int port,
                                          boolean requireAuth, JwtFixture jwt) {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 64);
            ExecutorService pool = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("mock-sba-", 0).factory());
            http.setExecutor(pool);

            MockSbaCoreServer mock = new MockSbaCoreServer(http, pool, jwt, requireAuth);
            mock.installRoutes();
            http.start();
            log.info("MockSbaCoreServer listening on {} (auth={})", mock.baseUri(), requireAuth);
            return mock;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start MockSbaCoreServer", e);
        }
    }

    public URI baseUri() {
        InetSocketAddress addr = server.getAddress();
        return URI.create("http://" + addr.getHostString() + ":" + addr.getPort());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public JwtFixture jwt() {
        return jwtFixture;
    }

    public void setNfProfiles(List<NFProfile> profiles) {
        this.nfProfiles.set(List.copyOf(profiles));
    }

    public void setMtBehavior(MtDeliveryBehavior behavior) {
        this.mtBehavior.set(behavior);
    }

    /**
     * When set, every MO/MT call dumps to the log:
     * <ul>
     *   <li>the multipart {@code Content-Type} header,</li>
     *   <li>a 16-byte-per-row hex+ASCII dump of the binary TPDU sub-part,</li>
     *   <li>the jss7-decoded summary of the TPDU (MTI, address, DCS, text).</li>
     * </ul>
     * This is the ground truth a Wireshark capture should match byte-for-byte.
     */
    public void setWireDump(boolean enabled) {
        this.wireDump = enabled;
    }

    public String issueToken(String subject, String... scopes) {
        return jwtFixture.issueToken(subject, Duration.ofMinutes(5), scopes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        log.info("MockSbaCoreServer stopped");
    }

    private void installRoutes() {
        server.createContext("/ue-contexts", route(this::routeUeContexts));
        server.createContext("/mock/ue-contexts", route(this::routeMockMt));
        server.createContext("/nnrf-disc", route(this::routeNrfDiscovery));
        server.createContext("/nnrf-nfm", route(this::routeNrfRegistration));
        server.createContext("/oauth2", route(this::routeOauth2));
    }

    // S2095: the HttpExchange is owned and closed by the JDK HttpServer after
    // the response stream is committed; wrapping it in try-with-resources here
    // double-closes the underlying socket. Keep the explicit try/catch.
    @SuppressWarnings("java:S2095")
    private HttpHandler route(HttpHandler delegate) {
        return exchange -> {
            byte[] body = readBody(exchange);
            try {
                delegate.handle(new ExchangeWithBody(exchange, body));
            } catch (Exception e) {
                log.warn("Handler failure for {} {}: {}", exchange.getRequestMethod(),
                        exchange.getRequestURI(), e.getMessage(), e);
                writeProblem(exchange, 500, "MOCK-5000", e.getMessage());
            } finally {
                int status = exchange.getResponseCode();
                recordRequest(exchange, body, status > 0 ? status : 0);
            }
        };
    }

    // ---------------------------------------------------------------- routes

    private void routeUeContexts(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith(SENDSMS)) {
            String supi = extractSupi(path, UE_CONTEXTS, SENDSMS);
            handleMoSendSms(exchange, supi);
        } else {
            String supi = extractSupi(path, UE_CONTEXTS, null);
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleDeregistration(exchange, supi);
            } else {
                handleRegistration(exchange, supi);
            }
        }
    }

    private void handleDeregistration(HttpExchange exchange, String supi) throws IOException {
        if (requireAuth && !authenticate(exchange)) {
            return;
        }
        try {
            SupiValidator.requireValid(supi);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        RegisteredUe removed = registry.remove(supi);
        if (removed == null) {
            writeProblem(exchange, 404, "SMSF-4041", "UE is not registered");
            return;
        }
        log.info("[mock] deregistered supi={} sessionId={}", supi, removed.getSessionId());
        // 204 No Content per TS29.540 Nsmsf_SMService UEContextDeletion.
        exchange.getResponseHeaders().add("X-Removed-Session-Id", removed.getSessionId());
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void handleRegistration(HttpExchange exchange, String supi) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeProblem(exchange, 405, MOCK_METHOD_NOT_ALLOWED, "Use POST to register UE");
            return;
        }
        if (requireAuth && !authenticate(exchange)) {
            return;
        }
        try {
            SupiValidator.requireValid(supi);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        byte[] body = bodyOf(exchange);
        RegistrationRequest req;
        try {
            req = SdkConverter.stringToObject(new String(body, StandardCharsets.UTF_8),
                    RegistrationRequest.class);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), "Cannot parse registration JSON");
            return;
        }
        if (req.getUeContext() == null) {
            req = RegistrationRequest.builder()
                    .correlationId(req.getCorrelationId())
                    .ueContext(SdkConverter.stringToObject(
                            new String(body, StandardCharsets.UTF_8), UeSmsContextData.class))
                    .build();
        }
        try {
            UeContextValidator.requireValid(req.getUeContext());
            if (!supi.equals(req.getUeContext().getSupi())) {
                throw new SdkException("MOCK-4006", "Invalid registration",
                        "SUPI in URL does not match payload");
            }
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        registry.put(supi, RegisteredUe.builder()
                .supi(supi).context(req.getUeContext())
                .sessionId(sessionId).registeredAt(Instant.now()).build());
        registrationCount.incrementAndGet();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("correlationId", req.getCorrelationId());
        response.put("sessionId", sessionId);
        response.put("supi", supi);
        response.put("accepted", true);
        writeJson(exchange, 201, response);
    }

    private void handleMoSendSms(HttpExchange exchange, String supi) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeProblem(exchange, 405, MOCK_METHOD_NOT_ALLOWED, "Use POST to send MO");
            return;
        }
        if (requireAuth && !authenticate(exchange)) {
            return;
        }
        try {
            SupiValidator.requireValid(supi);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        if (!registry.containsKey(supi)) {
            writeProblem(exchange, 404, "SMSF-4041", "UE is not registered");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst(CONTENT_TYPE_HDR);
        MoMultipartPayload payload;
        try {
            payload = MultipartRelatedDecoder.decode(bodyOf(exchange), contentType);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        SmsTpdu decoded;
        try {
            decoded = codec.decode(payload.getBinaryData());
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        moCount.incrementAndGet();
        moCaptures.add(new MoCapture(supi, payload.getJsonData(), payload.getBinaryData(), decoded));
        dumpWireReceive("MO", supi, contentType, payload.getBinaryData());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("smsRecordId", payload.getJsonData().getSmsRecordId());
        response.put("accepted", true);
        response.put("tpduType", decoded.getSmsTpduType().name());
        writeJson(exchange, 202, response);
    }

    private void routeMockMt(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeProblem(exchange, 405, MOCK_METHOD_NOT_ALLOWED, "Use POST to deliver MT");
            return;
        }
        if (requireAuth && !authenticate(exchange)) {
            return;
        }
        String supi = extractSupi(exchange.getRequestURI().getPath(),
                "/mock/ue-contexts/", SENDSMS);
        try {
            SupiValidator.requireValid(supi);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst(CONTENT_TYPE_HDR);
        MoMultipartPayload payload;
        try {
            payload = MultipartRelatedDecoder.decode(bodyOf(exchange), contentType);
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        SmsTpdu decoded;
        try {
            decoded = codec.decode(payload.getBinaryData());
        } catch (SdkException e) {
            writeProblem(exchange, 400, e.getCode(), e.getDetails());
            return;
        }
        int count = mtCount.incrementAndGet();
        mtCaptures.add(new MtCapture(supi, payload.getJsonData(), payload.getBinaryData(), decoded));
        dumpWireReceive("MT", supi, contentType, payload.getBinaryData());
        MtDeliveryBehavior behavior = mtBehavior.get();
        MtDeliveryBehavior.Decision decision = behavior.decide(supi, count);
        if (decision.delay().toMillis() > 0) {
            sleep(decision.delay());
        }
        if (decision.deliver()) {
            // 200 + JSON DLR (not 204) so audit captures non-empty response.
            Map<String, Object> dlr = new LinkedHashMap<>();
            dlr.put("smsRecordId", payload.getJsonData().getSmsRecordId());
            dlr.put("status", "DELIVERED");
            dlr.put("tpduType", decoded.getSmsTpduType().name());
            dlr.put("tpduLength", payload.getBinaryData() == null ? 0 : payload.getBinaryData().length);
            dlr.put("deliveryTimestamp", Instant.now().toString());
            writeJson(exchange, 200, dlr);
        } else {
            writeProblem(exchange, decision.statusCode(), decision.errorCode(),
                    decision.errorDetail());
        }
    }

    private void routeNrfDiscovery(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(SbaSupportRoutes.NRF_DISCOVERY_PATH)
                && !path.equals(SbaSupportRoutes.NRF_DISCOVERY_PATH + "/")) {
            writeProblem(exchange, 404, MOCK_NOT_FOUND, "Unknown discovery path: " + path);
            return;
        }
        writeResult(exchange, supportRoutes.nrfDiscovery(
                exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery()));
    }

    private void routeNrfRegistration(HttpExchange exchange) throws IOException {
        writeResult(exchange, supportRoutes.nrfRegistration(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                bodyOf(exchange),
                baseUri().toString()));
    }

    private void routeOauth2(HttpExchange exchange) throws IOException {
        writeResult(exchange, supportRoutes.oauth2(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath()));
    }

    // ---------------------------------------------------------------- wire dump

    /**
     * Prints what the server actually received over the wire for one sendsms
     * call: a hex+ASCII dump of the binary TPDU sub-part and the jss7-decoded
     * summary. Operators can paste any Wireshark frame's "Raw" bytes and
     * compare line-by-line against this log to prove the binary is on the wire.
     */
    private void dumpWireReceive(String kind, String supi, String contentType, byte[] tpdu) {
        if (!wireDump) {
            return;
        }
        log.info("[mock<-{}] supi={} Content-Type: {}", kind, supi, contentType);
        log.info("[mock<-{}] TPDU received ({} bytes):\n{}",
                kind, tpdu == null ? 0 : tpdu.length, HexDump.format(tpdu));
        log.info("[mock<-{}] jss7 decode: {}", kind, TpduInspector.inspect(tpdu));
    }

    // ---------------------------------------------------------------- auth

    private boolean authenticate(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            authFailures.incrementAndGet();
            writeProblem(exchange, 401, "SMSF-4010", "Missing bearer token");
            return false;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            jwtAuthenticator.authenticate(token);
            return true;
        } catch (AuthenticationException e) {
            authFailures.incrementAndGet();
            writeProblem(exchange, 401, "SMSF-4010", e.getDetails());
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void recordRequest(HttpExchange exchange, byte[] body, int status) {
        recorded.add(RecordedRequest.builder()
                .method(exchange.getRequestMethod())
                .path(exchange.getRequestURI().getPath())
                .query(exchange.getRequestURI().getRawQuery())
                .headers(flatHeaders(exchange))
                .body(body)
                .receivedAt(Instant.now())
                .responseStatus(status)
                .build());
    }

    private Map<String, String> flatHeaders(HttpExchange exchange) {
        Map<String, String> flat = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) ->
                flat.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
        return flat;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private static byte[] bodyOf(HttpExchange exchange) {
        if (exchange instanceof ExchangeWithBody e) {
            return e.body();
        }
        try {
            return readBody(exchange);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extractSupi(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix)) {
            return "";
        }
        String tail = path.substring(prefix.length());
        if (suffix != null && tail.endsWith(suffix)) {
            tail = tail.substring(0, tail.length() - suffix.length());
        }
        return tail;
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = SdkConverter.valueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(CONTENT_TYPE_HDR, "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Adapter: emits an {@link HttpResult} from {@link SbaSupportRoutes} onto the JDK exchange. */
    private static void writeResult(HttpExchange exchange,
                                    com.paicore.sba.stack.testing.controller.dto.HttpResult result)
            throws IOException {
        result.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = result.body() == null ? new byte[0] : result.body();
        exchange.sendResponseHeaders(result.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void writeProblem(HttpExchange exchange, int status, String code, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://sba-stack.local/errors/" + code);
        problem.put("title", code);
        problem.put("status", status);
        problem.put("detail", detail == null ? "" : detail);
        byte[] bytes = SdkConverter.valueAsString(problem).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(CONTENT_TYPE_HDR, "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** HttpExchange wrapper that carries the already-consumed body. */
    static final class ExchangeWithBody extends DelegatingHttpExchange {
        private final byte[] body;

        ExchangeWithBody(HttpExchange delegate, byte[] body) {
            super(delegate);
            this.body = body;
        }

        public byte[] body() {
            return body;
        }
    }

    /** Snapshot of an MO sendsms call. */
    public record MoCapture(String supi, SmsRecordData smsRecord, byte[] tpdu, SmsTpdu decoded) {
        @Override public boolean equals(Object o) { return SmsCaptures.equalSnapshots(this, o, MoCapture.class); }
        @Override public int hashCode() { return SmsCaptures.hash(supi, smsRecord, decoded, tpdu); }
        @Override public String toString() { return SmsCaptures.render("MoCapture", supi, smsRecord, decoded, tpdu); }
    }

    /** Snapshot of an MT delivery call. */
    public record MtCapture(String supi, SmsRecordData smsRecord, byte[] tpdu, SmsTpdu decoded) {
        @Override public boolean equals(Object o) { return SmsCaptures.equalSnapshots(this, o, MtCapture.class); }
        @Override public int hashCode() { return SmsCaptures.hash(supi, smsRecord, decoded, tpdu); }
        @Override public String toString() { return SmsCaptures.render("MtCapture", supi, smsRecord, decoded, tpdu); }
    }
}
