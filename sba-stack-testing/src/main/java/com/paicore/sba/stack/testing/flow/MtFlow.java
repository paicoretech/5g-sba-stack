package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.NormalizedError;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives MT delivery against the mock receiver at
 * {@code POST /mock/ue-contexts/{supi}/sendsms}. Reproduces what a real
 * SMSF kit does when delivering a TPDU to an AMF over N1, but against the
 * in-process mock so the test framework can validate authentication,
 * multipart parsing, TPDU decode, and retry behaviour end-to-end.
 *
 * <p>Includes a tiny retry engine because CLAUDE-Testing.md flags
 * {@code Retry handling} as a mandatory MT validation area.
 */
@Slf4j
public class MtFlow implements AutoCloseable {

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient http;
    private final ExecutorService executor;
    private final int maxAttempts;
    private final Duration backoff;
    private final AtomicInteger inFlight = new AtomicInteger();

    public MtFlow(URI baseUri, String bearerToken) {
        this(baseUri, bearerToken, 3, Duration.ofMillis(40), null);
    }

    public MtFlow(URI baseUri, String bearerToken, HttpClient sharedHttp) {
        this(baseUri, bearerToken, 3, Duration.ofMillis(40), sharedHttp);
    }

    public MtFlow(URI baseUri, String bearerToken, int maxAttempts, Duration backoff) {
        this(baseUri, bearerToken, maxAttempts, backoff, null);
    }

    public MtFlow(URI baseUri, String bearerToken, int maxAttempts, Duration backoff,
                  HttpClient sharedHttp) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mt-flow-", 0).factory());
        this.http = sharedHttp != null ? sharedHttp : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public int inFlight() { return inFlight.get(); }

    public MtResponse deliver(MtMessageRequest request) {
        if (request.getSmsRecord() == null || request.getTpduBinary() == null) {
            return MtResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .status(DeliveryStatus.REJECTED)
                    .error(NormalizedError.of(ErrorCodes.INVALID_MT_PAYLOAD,
                            "smsRecord and tpduBinary are required"))
                    .build();
        }
        MultipartRelatedEncoder.Encoded encoded;
        try {
            encoded = MultipartRelatedEncoder.encode(request.getSmsRecord(), request.getTpduBinary());
        } catch (SdkException e) {
            return MtResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .smsRecordId(request.getSmsRecord().getSmsRecordId())
                    .status(DeliveryStatus.REJECTED)
                    .error(NormalizedError.from(e))
                    .build();
        }
        URI uri = baseUri.resolve("/mock/ue-contexts/" + request.getTargetSupi() + "/sendsms");

        MtResponse last = null;
        inFlight.incrementAndGet();
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", encoded.contentType())
                        .header("Authorization", "Bearer " + bearerToken)
                        .header("X-Attempt", String.valueOf(attempt))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()))
                        .build();
                try {
                    HttpResponse<String> response = http.send(httpRequest,
                            HttpResponse.BodyHandlers.ofString());
                    log.info("[mt] negotiated HTTP version = {}", response.version());
                    last = mapResponse(request, response);
                    if (last.getStatus() != DeliveryStatus.UNDELIVERABLE) {
                        return last;
                    }
                } catch (IOException e) {
                    log.warn("MT attempt {} failed: {}", attempt, e.getMessage());
                    last = mtTransportFailure(request, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("MT attempt {} interrupted: {}", attempt, e.getMessage());
                    last = mtTransportFailure(request, e);
                    break;
                }
                sleep(backoff.multipliedBy(attempt));
            }
            return last;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public CompletableFuture<MtResponse> deliverAsync(MtMessageRequest request) {
        return CompletableFuture.supplyAsync(() -> deliver(request), executor);
    }

    private static MtResponse mtTransportFailure(MtMessageRequest request, Exception e) {
        return MtResponse.builder()
                .correlationId(request.getCorrelationId())
                .smsRecordId(request.getSmsRecord().getSmsRecordId())
                .status(DeliveryStatus.UNDELIVERABLE)
                .error(NormalizedError.of(ErrorCodes.HTTP2_TRANSPORT_FAILURE, e.getMessage()))
                .build();
    }

    private MtResponse mapResponse(MtMessageRequest request, HttpResponse<String> response) {
        boolean delivered = response.statusCode() == 204 || response.statusCode() / 100 == 2;
        DeliveryStatus status = delivered ? DeliveryStatus.DELIVERED : DeliveryStatus.UNDELIVERABLE;
        return MtResponse.builder()
                .correlationId(request.getCorrelationId())
                .smsRecordId(request.getSmsRecord().getSmsRecordId())
                .status(status)
                .error(delivered ? null : NormalizedError.of(ErrorCodes.MT_DELIVERY_FAILED,
                        "Mock MT receiver responded HTTP " + response.statusCode()))
                .build();
    }

    private static void sleep(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
