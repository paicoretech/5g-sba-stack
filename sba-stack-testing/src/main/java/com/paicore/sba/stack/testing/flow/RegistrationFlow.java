package com.paicore.sba.stack.testing.flow;

import com.paicore.sba.stack.dto.NormalizedError;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.utils.ErrorCodes;
import com.paicore.sba.stack.utils.SdkConverter;
import com.paicore.sba.stack.validation.UeContextValidator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Realistic end-to-end demonstration of a UE registration call against the
 * mock {@code POST /ue-contexts/{supi}} endpoint.
 *
 * <p>The flow shows how an SMSF application would:
 * <ol>
 *   <li>Validate the {@link UeSmsContextData} payload with the stack's
 *       {@link UeContextValidator}.</li>
 *   <li>Acquire a bearer token (issued by the mock OAuth2 server).</li>
 *   <li>Submit the registration via the JDK HTTP/2 client.</li>
 *   <li>Normalise wire failures into the SDK's {@link NormalizedError}.</li>
 * </ol>
 *
 * <p>The flow exposes both blocking ({@link #register}) and async
 * ({@link #registerAsync}) entry points so callers can choose between thread-pool
 * and virtual-thread execution styles per CLAUDE-Testing.md §"Thread Pool
 * Requirements".
 */
@Slf4j
public class RegistrationFlow implements AutoCloseable {

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient http;
    private final ExecutorService executor;

    public RegistrationFlow(URI baseUri, String bearerToken) {
        this(baseUri, bearerToken, null);
    }

    /**
     * Lets callers inject a shared {@link HttpClient} so several flows can
     * multiplex over a single HTTP/2 connection (a runner-wide audit trail
     * with one h2c Upgrade dance and one TCP connection for the whole journey).
     */
    public RegistrationFlow(URI baseUri, String bearerToken, HttpClient sharedHttp) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reg-flow-", 0).factory());
        this.http = sharedHttp != null ? sharedHttp : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .executor(executor)
                .build();
    }

    public RegistrationResponse register(RegistrationRequest request) {
        UeContextValidator.requireValid(request.getUeContext());
        String supi = request.getUeContext().getSupi();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/ue-contexts/" + supi))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(
                        SdkConverter.valueAsString(request), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("[register] negotiated HTTP version = {}", response.version());
            return mapResponse(request, supi, response);
        } catch (IOException e) {
            log.warn("Registration call failed: {}", e.getMessage());
            return registrationFailure(request, supi, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Registration call interrupted: {}", e.getMessage());
            return registrationFailure(request, supi, e);
        }
    }

    private static RegistrationResponse registrationFailure(RegistrationRequest request, String supi, Exception e) {
        return RegistrationResponse.builder()
                .correlationId(request.getCorrelationId())
                .supi(supi)
                .accepted(false)
                .error(NormalizedError.of(ErrorCodes.HTTP2_TRANSPORT_FAILURE, e.getMessage()))
                .build();
    }

    public CompletableFuture<RegistrationResponse> registerAsync(RegistrationRequest request) {
        return CompletableFuture.supplyAsync(() -> register(request), executor);
    }

    /**
     * Issues {@code DELETE /ue-contexts/{supi}} per 3GPP TS29.540
     * Nsmsf_SMService UEContextDeletion. Returns {@code true} on 2xx.
     */
    public boolean deregister(String supi) {
        // Delegated to deregisterInternal so version logging stays consistent.
        return deregisterInternal(supi);
    }

    private boolean deregisterInternal(String supi) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/ue-contexts/" + supi))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + bearerToken)
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("[deregister] negotiated HTTP version = {}", response.version());
            return response.statusCode() / 100 == 2;
        } catch (IOException e) {
            log.warn("Deregister call failed: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Deregister call interrupted: {}", e.getMessage());
            return false;
        }
    }

    private RegistrationResponse mapResponse(RegistrationRequest request, String supi,
                                             HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            return RegistrationResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .supi(supi)
                    .accepted(false)
                    .error(NormalizedError.of(
                            "SMSF-" + response.statusCode(),
                            "Mock NRF/UDM responded " + response.statusCode() + ": " + response.body()))
                    .build();
        }
        Map<String, Object> body = SdkConverter.stringToObject(response.body(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        return RegistrationResponse.builder()
                .correlationId(String.valueOf(body.getOrDefault("correlationId", request.getCorrelationId())))
                .sessionId(String.valueOf(body.getOrDefault("sessionId", "")))
                .supi(supi)
                .accepted(Boolean.TRUE.equals(body.getOrDefault("accepted", true)))
                .build();
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
