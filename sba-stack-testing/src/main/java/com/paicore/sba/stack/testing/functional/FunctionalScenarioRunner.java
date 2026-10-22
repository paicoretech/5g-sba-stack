package com.paicore.sba.stack.testing.functional;

import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.codec.MessageType;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import com.paicore.sba.stack.testing.flow.MoFlow;
import com.paicore.sba.stack.testing.flow.MtFlow;
import com.paicore.sba.stack.testing.flow.RegistrationFlow;
import com.paicore.sba.stack.testing.mock.MockSbaH2cServer;
import com.paicore.sba.stack.testing.util.HexDump;
import com.paicore.sba.stack.testing.util.TpduInspector;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-alone scenario runner that produces exactly the minimal Wireshark-
 * friendly trace described in {@code test-trace.md}: six HTTP/2 calls in
 * sequence, each spaced 2 s apart so every request is its own packet group:
 *
 * <ol>
 *   <li>{@code POST  /ue-contexts/{supi}}                  — register</li>
 *   <li>{@code POST  /ue-contexts/{supi}/sendsms}          — MO UCS-2</li>
 *   <li>{@code POST  /ue-contexts/{supi}/sendsms}          — MO GSM-7</li>
 *   <li>{@code POST  /mock/ue-contexts/{supi}/sendsms}     — MT UCS-2</li>
 *   <li>{@code POST  /mock/ue-contexts/{supi}/sendsms}     — MT GSM-7</li>
 *   <li>{@code DELETE /ue-contexts/{supi}}                 — deregister</li>
 * </ol>
 *
 * <h2>Capturing with Wireshark</h2>
 *
 * <pre>
 *  sudo wireshark -i lo -k -f "tcp port 9090"
 *  # Display filter:  http.request.uri contains "/ue-contexts"
 * </pre>
 *
 * <h2>Running</h2>
 *
 * <pre>
 *  mvn -pl sba-stack-testing -am -DskipTests install
 *  mvn -pl sba-stack-testing -q exec:java \
 *      -Dexec.mainClass=com.paicore.sba.stack.testing.functional.FunctionalScenarioRunner \
 *      -Dexec.args="0.0.0.0 9090 60"
 * </pre>
 *
 * <p>Args (all optional): {@code <host> <port> <idleSeconds>}. Defaults are
 * {@code 0.0.0.0 9090 60}.
 */
@Slf4j
public final class FunctionalScenarioRunner {

    private static final String UCS_2_DEST = "+8613915900000";
    private static final String UCS_2_ORIG = "+50212345678";
    private static final String HELLO_WORLD = "Hello world";
    private static final String SEPARATOR =
            "=================================================================";

    private static final long STEP_PAUSE_MS = 2_000;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        long idleSeconds = args.length > 2 ? Long.parseLong(args[2]) : 60;

        try (MockSbaH2cServer mock = MockSbaH2cServer.start(host, port, true, TestFixtures.jwt())) {
            mock.setWireDump(true);
            URI clientUri = URI.create("http://127.0.0.1:" + mock.getPort());
            String token = mock.issueToken("functional-runner",
                    "smsf.register", "smsf.mo", "smsf.mt");

            // Shared HTTP/2 client: ONE TCP connection upgraded once with
            // h2c-Upgrade, then every subsequent call multiplexes a fresh
            // HTTP/2 stream on the same connection. The audit trail in
            // Wireshark is: TCP handshake -> HTTP/1.1 GET ... Upgrade: h2c
            // -> 101 Switching Protocols -> HTTP/2 frames for everything else.
            java.net.http.HttpClient sharedHttp = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            banner(mock, clientUri);
            seedNrf(mock);

            // 1 — Register UE
            stepRegister(clientUri, token, sharedHttp);
            pauseForCapture();

            // 2 — MO UCS-2 HELLO_WORLD
            stepMo(clientUri, token, sharedHttp, "ucs2", TpduFixture.DCS_UCS2,
                    HELLO_WORLD, "/tmp/sba-mo-ucs2");
            pauseForCapture();

            // 3 — MO GSM-7 HELLO_WORLD
            stepMo(clientUri, token, sharedHttp, "gsm7", TpduFixture.DCS_GSM7,
                    HELLO_WORLD, "/tmp/sba-mo-gsm7");
            pauseForCapture();

            // 4 — MT UCS-2 HELLO_WORLD
            stepMt(clientUri, token, sharedHttp, "ucs2", TpduFixture.DCS_UCS2,
                    HELLO_WORLD, "/tmp/sba-mt-ucs2");
            pauseForCapture();

            // 5 — MT GSM-7 HELLO_WORLD
            stepMt(clientUri, token, sharedHttp, "gsm7", TpduFixture.DCS_GSM7,
                    HELLO_WORLD, "/tmp/sba-mt-gsm7");
            pauseForCapture();

            // 6 — Deregister UE
            stepDeregister(clientUri, token, sharedHttp);

            dumpTrafficSummary(mock);
            dumpManualCurlHints(mock, clientUri, token);
            log.info(" Idling for {}s so Wireshark/tcpdump can finish capturing. Ctrl+C to exit.",
                    idleSeconds);
            log.info(SEPARATOR);
            Thread.sleep(Duration.ofSeconds(idleSeconds).toMillis());
        }
    }

    // ------------------------------------------------------------------ steps

    private static void stepRegister(URI baseUri, String token, java.net.http.HttpClient sharedHttp) {
        log.info("---------------------------------------- step 1/6 :: REGISTER");
        try (RegistrationFlow reg = new RegistrationFlow(baseUri, token, sharedHttp)) {
            RegistrationResponse resp = reg.register(TestFixtures.defaultRegistrationRequest());
            log.info("[register] POST /ue-contexts/{} -> accepted={} sessionId={}",
                    TestFixtures.DEFAULT_SUPI, resp.isAccepted(), resp.getSessionId());
        }
    }

    private static void stepMo(URI baseUri, String token, java.net.http.HttpClient sharedHttp,
                               String tag, int dcs, String message, String filePrefix) {
        log.info("---------------------------------------- step :: MO {} '{}'", tag, message);
        byte[] tpdu = TpduFixture.encode(MessageType.SMS_SUBMIT, UCS_2_DEST, message, dcs);
        var smsRecord = TestFixtures.smsRecordFor("mo-" + tag);
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(smsRecord, tpdu);

        log.info("[mo:{}] POST /ue-contexts/{}/sendsms — TPDU {} B",
                tag, TestFixtures.DEFAULT_SUPI, tpdu.length);
        log.info("[mo:{}] TPDU:\n{}", tag, HexDump.format(tpdu));
        log.info("[mo:{}] jss7 round-trip decode: {}", tag, TpduInspector.inspect(tpdu));
        writeFile(filePrefix + ".tpdu", tpdu);
        writeFile(filePrefix + ".multipart", encoded.body());

        try (MoFlow mo = new MoFlow(baseUri, token, sharedHttp)) {
            MoResponse resp = mo.sendMo(MoMessageRequest.builder()
                    .correlationId("corr-mo-" + tag)
                    .smsRecord(smsRecord)
                    .build(), tpdu);
            log.info("[mo:{}] -> smsRecordId={} accepted={}",
                    tag, resp.getSmsRecordId(), resp.isAccepted());
        }
    }

    private static void stepMt(URI baseUri, String token, java.net.http.HttpClient sharedHttp,
                               String tag, int dcs, String message, String filePrefix) {
        log.info("---------------------------------------- step :: MT {} '{}'", tag, message);
        byte[] tpdu = TpduFixture.encode(MessageType.SMS_DELIVER, UCS_2_ORIG, message, dcs);
        var smsRecord = TestFixtures.smsRecordFor("mt-" + tag);
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(smsRecord, tpdu);

        log.info("[mt:{}] POST /mock/ue-contexts/{}/sendsms — TPDU {} B",
                tag, TestFixtures.DEFAULT_SUPI, tpdu.length);
        log.info("[mt:{}] TPDU:\n{}", tag, HexDump.format(tpdu));
        log.info("[mt:{}] jss7 round-trip decode: {}", tag, TpduInspector.inspect(tpdu));
        writeFile(filePrefix + ".tpdu", tpdu);
        writeFile(filePrefix + ".multipart", encoded.body());

        try (MtFlow mt = new MtFlow(baseUri, token, sharedHttp)) {
            MtResponse resp = mt.deliver(MtMessageRequest.builder()
                    .correlationId("corr-mt-" + tag)
                    .targetSupi(TestFixtures.DEFAULT_SUPI)
                    .smsRecord(smsRecord)
                    .tpduBinary(tpdu)
                    .requestDlr(true)
                    .build());
            log.info("[mt:{}] -> smsRecordId={} status={}",
                    tag, resp.getSmsRecordId(), resp.getStatus());
        }
    }

    private static void stepDeregister(URI baseUri, String token, java.net.http.HttpClient sharedHttp) {
        log.info("---------------------------------------- step 6/6 :: DEREGISTER");
        try (RegistrationFlow reg = new RegistrationFlow(baseUri, token, sharedHttp)) {
            boolean ok = reg.deregister(TestFixtures.DEFAULT_SUPI);
            log.info("[deregister] DELETE /ue-contexts/{} -> ok={}", TestFixtures.DEFAULT_SUPI, ok);
        }
    }

    // ----------------------------------------------------- summary / helpers

    private static void banner(MockSbaH2cServer mock, URI clientUri) {
        log.info(SEPARATOR);
        log.info(" 5G SBA Stack — Functional Scenario Runner (minimal trace mode)");
        log.info(" Mock SBA core bound at {} (client URI {})", mock.baseUri(), clientUri);
        log.info(" Capture with: sudo tcpdump -i lo -A -s0 'tcp port {}'", mock.getPort());
        log.info(SEPARATOR);
    }

    private static void seedNrf(MockSbaH2cServer mock) {
        mock.setNfProfiles(List.of(
                NFProfile.builder().nfInstanceId("udm-eastus-1").nfType(NFType.UDM)
                        .apiRoot("http://udm-eastus-1.local/nudm-sdm/v2").build(),
                NFProfile.builder().nfInstanceId("amf-eastus-1").nfType(NFType.AMF)
                        .apiRoot("http://amf-eastus-1.local/namf-comm/v1").build()
        ));
    }

    private static void dumpTrafficSummary(MockSbaH2cServer mock) {
        Map<String, int[]> grouped = new LinkedHashMap<>();
        for (var rec : mock.getRecorded()) {
            String key = rec.getMethod() + " " + rec.getPath();
            grouped.computeIfAbsent(key, k -> new int[1])[0]++;
        }
        log.info(SEPARATOR);
        log.info(" Wire-level summary (per HTTP request observed by the mock):");
        grouped.forEach((endpoint, tally) ->
                log.info("   {} x {}", String.format("%6d", tally[0]), endpoint));
        log.info("   Totals: registrations={} mo-accepted={} mo-rejected={} mt={} authFailures={}",
                mock.getRegistrationService().registeredCount(),
                mock.getMoService().acceptedCount(),
                mock.getMoService().rejectedCount(),
                mock.getMtCount().get(), mock.getAuthFailures().get());
        log.info(SEPARATOR);
    }

    private static void dumpManualCurlHints(MockSbaH2cServer mock, URI clientUri, String token) {
        log.info(" Live endpoints (mock bound on 0.0.0.0:{}, capture on 'lo'):", mock.getPort());
        log.info("   1. POST    {}/ue-contexts/{}", clientUri, TestFixtures.DEFAULT_SUPI);
        log.info("   2,3 POST   {}/ue-contexts/{}/sendsms  (MO)", clientUri, TestFixtures.DEFAULT_SUPI);
        log.info("   4,5 POST   {}/mock/ue-contexts/{}/sendsms  (MT)", clientUri, TestFixtures.DEFAULT_SUPI);
        log.info("   6.  DELETE {}/ue-contexts/{}", clientUri, TestFixtures.DEFAULT_SUPI);
        log.info(" Bearer token (HS256, issuer {}): {}", TestFixtures.jwt().issuer(), token);
        log.info(" Multipart bodies saved on disk for curl replay:");
        log.info("   /tmp/sba-mo-ucs2.multipart  /tmp/sba-mo-ucs2.tpdu");
        log.info("   /tmp/sba-mo-gsm7.multipart  /tmp/sba-mo-gsm7.tpdu");
        log.info("   /tmp/sba-mt-ucs2.multipart  /tmp/sba-mt-ucs2.tpdu");
        log.info("   /tmp/sba-mt-gsm7.multipart  /tmp/sba-mt-gsm7.tpdu");
    }

    private static void pauseForCapture() {
        try {
            Thread.sleep(STEP_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeFile(String path, byte[] data) {
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(path), data);
            log.info("[file] wrote {} ({} bytes)", path, data.length);
        } catch (java.io.IOException e) {
            log.warn("[file] could not write {}: {}", path, e.getMessage());
        }
    }

    private FunctionalScenarioRunner() {
        throw new IllegalStateException("Main class");
    }
}
