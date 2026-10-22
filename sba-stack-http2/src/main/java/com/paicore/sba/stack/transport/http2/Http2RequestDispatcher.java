package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.NormalizedError;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.spi.TransportProvider;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.util.Http2Constants;
import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Maps SMSF N-message MT requests onto HTTP/2 N1 paths per 3GPP TS29540
 * and converts wire responses back into the SDK's MtResponse contract.
 */
@Slf4j
@RequiredArgsConstructor
public class Http2RequestDispatcher implements TransportProvider {

    // S1075: 3GPP TS29.540 §6 fixes the Nsmsf_SMService path template; it is
    // not an operator-configurable URI but an API contract.
    @SuppressWarnings("java:S1075")
    private static final String MT_PATH = "/nsmsf-sms/v2/ue-contexts/{supi}/sendsms";

    private final Http2Client client;

    @Override
    public void start() {
        if (!client.isConnected()) {
            client.connect();
        }
    }

    @Override
    public CompletableFuture<MtResponse> dispatchMt(MtMessageRequest request) {
        String supi = request.getTargetSupi();
        if (supi == null || supi.isBlank()) {
            return CompletableFuture.completedFuture(MtResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .status(DeliveryStatus.REJECTED)
                    .error(NormalizedError.of(ErrorCodes.INVALID_MT_PAYLOAD, "targetSupi is required"))
                    .build());
        }
        MultipartRelatedEncoder.Encoded encoded;
        try {
            encoded = MultipartRelatedEncoder.encode(request.getSmsRecord(), request.getTpduBinary());
        } catch (SdkException e) {
            return CompletableFuture.completedFuture(MtResponse.builder()
                    .correlationId(request.getCorrelationId())
                    .status(DeliveryStatus.REJECTED)
                    .error(NormalizedError.of(e.getCode(), e.getDetails()))
                    .build());
        }
        Http2Request httpRequest = Http2Request.builder()
                .method("POST")
                .path(MT_PATH.replace("{supi}", supi))
                .correlationId(request.getCorrelationId())
                .headers(Map.of(Http2Constants.HEADER_CONTENT_TYPE, encoded.contentType()))
                .body(encoded.body())
                .build();
        return client.send(httpRequest).thenApply(resp -> MtResponse.builder()
                .correlationId(request.getCorrelationId())
                .smsRecordId(request.getSmsRecord() != null ? request.getSmsRecord().getSmsRecordId() : null)
                .status(resp.getStatus() / 100 == 2 ? DeliveryStatus.ACCEPTED : DeliveryStatus.UNDELIVERABLE)
                .error(resp.getStatus() / 100 == 2 ? null
                        : NormalizedError.of(ErrorCodes.MT_DELIVERY_FAILED,
                            "HTTP status " + resp.getStatus()))
                .build());
    }

    @Override
    public void close() {
        client.close();
    }
}
