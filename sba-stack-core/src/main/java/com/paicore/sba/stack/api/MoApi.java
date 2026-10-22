package com.paicore.sba.stack.api;

import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.mo.MoMultipartPayload;
import com.paicore.sba.stack.mo.MultipartRelatedDecoder;

public interface MoApi {

    MoResponse receiveMoMessage(MoMessageRequest request);

    /**
     * Decodes a 3GPP TS29540 sendsms multipart/related request body into its
     * JSON metadata (SmsRecordData) and the binary TPDU part. The returned
     * payload's {@code binaryData} can be fed directly to {@code SmsTpduCodec.decode}.
     */
    default MoMultipartPayload decodeRequest(byte[] body, String contentType) {
        return MultipartRelatedDecoder.decode(body, contentType);
    }
}
