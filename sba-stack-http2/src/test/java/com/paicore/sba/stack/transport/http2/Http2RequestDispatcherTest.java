package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.openapi.model.RefToBinaryData;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import com.paicore.sba.stack.transport.http2.util.Http2Constants;
import com.paicore.sba.stack.utils.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Http2RequestDispatcherTest {

    private Http2Client client;
    private Http2RequestDispatcher dispatcher;

    @BeforeEach
    void setup() {
        client = mock(Http2Client.class);
        dispatcher = new Http2RequestDispatcher(client);
    }

    @Test
    void startConnectsWhenNotConnected() {
        when(client.isConnected()).thenReturn(false);
        dispatcher.start();
        verify(client, times(1)).connect();
    }

    @Test
    void startSkipsConnectWhenAlreadyConnected() {
        when(client.isConnected()).thenReturn(true);
        dispatcher.start();
        verify(client, never()).connect();
    }

    @Test
    void dispatchMtRejectsMissingSupi() throws Exception {
        MtMessageRequest req = MtMessageRequest.builder()
                .correlationId("c-1")
                .smsRecord(new SmsRecordData().smsRecordId("rec-1"))
                .build();

        MtResponse response = dispatcher.dispatchMt(req).get();

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(response.getError()).isNotNull();
        assertThat(response.getError().getCode()).isEqualTo(ErrorCodes.INVALID_MT_PAYLOAD);
        assertThat(response.getCorrelationId()).isEqualTo("c-1");
        verify(client, never()).send(any());
    }

    @Test
    void dispatchMtRejectsBlankSupi() throws Exception {
        MtMessageRequest req = MtMessageRequest.builder()
                .targetSupi("   ")
                .smsRecord(new SmsRecordData().smsRecordId("rec-2"))
                .build();

        MtResponse response = dispatcher.dispatchMt(req).get();

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(response.getError().getCode()).isEqualTo(ErrorCodes.INVALID_MT_PAYLOAD);
    }

    @Test
    void dispatchMtBuildsMultipartHttp2RequestAndMapsSuccess() throws Exception {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("rec-3")
                .gpsi("msisdn-1")
                .smsPayload(new RefToBinaryData().contentId("sms"));
        byte[] tpdu = new byte[]{0x01, 0x02, (byte) 0xAA, (byte) 0xFF};
        MtMessageRequest req = MtMessageRequest.builder()
                .correlationId("corr-3")
                .targetSupi("imsi-460000000000001")
                .smsRecord(smsRecord)
                .tpduBinary(tpdu)
                .build();
        when(client.send(any(Http2Request.class))).thenReturn(
                CompletableFuture.completedFuture(Http2Response.builder().status(202).build()));

        MtResponse response = dispatcher.dispatchMt(req).get();

        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        verify(client).send(captor.capture());
        Http2Request sent = captor.getValue();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/nsmsf-sms/v2/ue-contexts/imsi-460000000000001/sendsms");
        assertThat(sent.getCorrelationId()).isEqualTo("corr-3");
        String contentType = sent.getHeaders().get(Http2Constants.HEADER_CONTENT_TYPE);
        assertThat(contentType)
                .startsWith(Http2Constants.CONTENT_TYPE_MULTIPART_RELATED)
                .contains("boundary=");

        String bodyAscii = new String(sent.getBody(), java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(bodyAscii)
                .contains("Content-Type: application/json")
                .contains("\"smsRecordId\":\"rec-3\"")
                .contains("Content-Type: " + Http2Constants.CONTENT_TYPE_3GPP_SMS)
                .contains("Content-Id: sms");
        // TPDU bytes appear unmodified between the boundaries
        assertThat(sent.getBody()).contains(tpdu);

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.ACCEPTED);
        assertThat(response.getSmsRecordId()).isEqualTo("rec-3");
        assertThat(response.getError()).isNull();
    }

    @Test
    void dispatchMtMapsNon2xxToUndeliverable() throws Exception {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("rec-x")
                .smsPayload(new RefToBinaryData().contentId("sms"));
        MtMessageRequest req = MtMessageRequest.builder()
                .targetSupi("imsi-1")
                .correlationId("c-x")
                .smsRecord(smsRecord)
                .tpduBinary(new byte[]{0x10})
                .build();
        when(client.send(any(Http2Request.class))).thenReturn(
                CompletableFuture.completedFuture(Http2Response.builder().status(503).build()));

        MtResponse response = dispatcher.dispatchMt(req).get();

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.UNDELIVERABLE);
        assertThat(response.getError().getCode()).isEqualTo(ErrorCodes.MT_DELIVERY_FAILED);
        assertThat(response.getError().getDetails()).contains("503");
    }

    @Test
    void dispatchMtRejectsMissingRecordOrTpdu() throws Exception {
        MtMessageRequest req = MtMessageRequest.builder()
                .targetSupi("imsi-2")
                .correlationId("c-y")
                .build();

        MtResponse response = dispatcher.dispatchMt(req).get();

        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(response.getError().getCode()).isEqualTo(ErrorCodes.INVALID_MT_PAYLOAD);
        verify(client, never()).send(any());
    }

    @Test
    void closeDelegatesToClient() {
        dispatcher.close();
        verify(client).close();
    }

    @Test
    void dispatchMtPropagatesClientFailure() {
        SmsRecordData smsRecord = new SmsRecordData()
                .smsRecordId("rec-z")
                .smsPayload(new RefToBinaryData().contentId("sms"));
        MtMessageRequest req = MtMessageRequest.builder()
                .targetSupi("imsi-3")
                .smsRecord(smsRecord)
                .tpduBinary(new byte[]{0x42})
                .build();
        CompletableFuture<Http2Response> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("transport down"));
        when(client.send(any(Http2Request.class))).thenReturn(failed);

        CompletableFuture<MtResponse> result = dispatcher.dispatchMt(req);

        assertThat(result.isCompletedExceptionally()).isTrue();
        ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, result::get);
        assertThat(ex.getCause()).hasMessageContaining("transport down");
    }
}
