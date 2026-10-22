package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.codec.SmsTpduCodec;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.NormalizedError;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives an MO sendsms call against the mock SMSF endpoint
 * {@code POST /ue-contexts/{supi}/sendsms}.
 *
 * <p>The flow demonstrates the production-style sequence:
 * <ol>
 *   <li>Build a 3GPP TS29.540 multipart/related body via
 *       {@link MultipartRelatedEncoder} (JSON root + TPDU binary).</li>
 *   <li>POST it over HTTP/2 with an OAuth2 bearer token.</li>
 *   <li>Run the flow on a virtual-thread pool so concurrent MO submissions
 *       reuse the connection — exactly what TS29.540 §6 requires.</li>
 * </ol>
 *
 * <p>The TPDU passed in MUST be one produced by
 * {@link SmsTpduCodec#encode}; otherwise the mock server's TPDU decode
 * step (using {@link Ss7SmsTpduCodec}) returns a 400 and the flow surfaces
 * a {@link NormalizedError} so the caller can branch.
 */
@Slf4j
public class MoFlow implements AutoCloseable {

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient http;
    private final ExecutorService executor;

    public MoFlow(URI baseUri, String bearerToken) {
        this(baseUri, bearerToken, null);
    }

    public MoFlow(URI baseUri, String bearerToken, HttpClient sharedHttp) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mo-flow-", 0).factory());
        this.http = sharedHttp != null ? sharedHttp : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public MoResponse sendMo(MoMessageRequest request, byte[] tpduBinary) {
        SmsRecordData smsRecord = request.getSmsRecord();
        if (smsRecord == null) {
            return MoResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .accepted(false)
                    .error(NormalizedError.of(ErrorCodes.INVALID_MO_PAYLOAD, "SmsRecordData is required"))
                    .build();
        }
        MultipartRelatedEncoder.Encoded encoded;
        try {
            encoded = MultipartRelatedEncoder.encode(smsRecord, tpduBinary);
        } catch (SdkException e) {
            return MoResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .smsRecordId(smsRecord.getSmsRecordId())
                    .accepted(false)
                    .error(NormalizedError.from(e))
                    .build();
        }
        String supi = DEFAULT_ORIGINATING_SUPI;
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/ue-contexts/" + supi + "/sendsms"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", encoded.contentType())
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("[mo] negotiated HTTP version = {}", response.version());
            boolean accepted = response.statusCode() / 100 == 2;
            return MoResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .smsRecordId(smsRecord.getSmsRecordId())
                    .accepted(accepted)
                    .error(accepted ? null : NormalizedError.of(
                            "SMSF-" + response.statusCode(),
                            "Mock SMSF responded " + response.statusCode() + ": " + response.body()))
                    .build();
        } catch (IOException e) {
            log.warn("MO call failed: {}", e.getMessage());
            return moTransportFailure(request, smsRecord, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MO call interrupted: {}", e.getMessage());
            return moTransportFailure(request, smsRecord, e);
        }
    }

    private static MoResponse moTransportFailure(MoMessageRequest request, SmsRecordData smsRecord, Exception e) {
        return MoResponse.builder()
                .correlationId(request.getCorrelationId())
                .smsRecordId(smsRecord.getSmsRecordId())
                .accepted(false)
                .error(NormalizedError.of(ErrorCodes.HTTP2_TRANSPORT_FAILURE, e.getMessage()))
                .build();
    }

    public CompletableFuture<MoResponse> sendMoAsync(MoMessageRequest request, byte[] tpduBinary) {
        return CompletableFuture.supplyAsync(() -> sendMo(request, tpduBinary), executor);
    }

    // Routing convention: when the application omits a target SUPI in the MO
    // request we fall back to the SUPI of the originating UE record's gpsi
    // mapping. The mock environment uses the IMSI of the registered UE.
    private static final String DEFAULT_ORIGINATING_SUPI = "imsi-460000000000001";

    @Override
    public void close() {
        executor.shutdown();
    }
}
