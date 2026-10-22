package com.paicore.sba.stack.mo;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.utils.ErrorCodes;
import com.paicore.sba.stack.utils.SdkConverter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encodes a 3GPP TS29540 Nsmsf_SMService sendsms body as multipart/related:
 * an application/json root part carrying {@link SmsRecordData}, followed by
 * an application/vnd.3gpp.sms part with the raw TPDU bytes. The
 * {@code Content-Id} of the binary part matches
 * {@code SmsRecordData.smsPayload.contentId} so the receiver can correlate
 * the two parts (RFC 2392).
 */
public final class MultipartRelatedEncoder {

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_VND_3GPP_SMS = "application/vnd.3gpp.sms";

    private static final byte[] CRLF = {'\r', '\n'};
    private static final String INVALID_MT_PAYLOAD = "Invalid MT payload";

    private MultipartRelatedEncoder() {
        throw new IllegalStateException("Utility class");
    }

    public static Encoded encode(SmsRecordData smsRecord, byte[] tpduBinary) {
        if (smsRecord == null) {
            throw new SdkException(ErrorCodes.INVALID_MT_PAYLOAD, INVALID_MT_PAYLOAD, "SmsRecordData is required");
        }
        String contentId = smsRecord.getSmsPayload().getContentId();
        if (contentId.isBlank()) {
            throw new SdkException(ErrorCodes.INVALID_MT_PAYLOAD, INVALID_MT_PAYLOAD, "smsPayload.contentId is required to reference the binary part");
        }
        return encode(smsRecord, contentId, tpduBinary);
    }

    public static Encoded encode(Object jsonRoot, String contentId, byte[] tpduBinary) {
        if (jsonRoot == null) {
            throw new SdkException(ErrorCodes.INVALID_MT_PAYLOAD, INVALID_MT_PAYLOAD, "JSON root part is required");
        }
        if (tpduBinary == null || tpduBinary.length == 0) {
            throw new SdkException(ErrorCodes.INVALID_MT_PAYLOAD, INVALID_MT_PAYLOAD, "TPDU binary is required");
        }
        if (contentId == null || contentId.isBlank()) {
            throw new SdkException(ErrorCodes.INVALID_MT_PAYLOAD, INVALID_MT_PAYLOAD, "contentId is required to reference the binary part");
        }
        String boundary = "----Boundary-" + UUID.randomUUID();
        byte[] body = buildBody(boundary, jsonRoot, contentId, tpduBinary);
        String contentType = "multipart/related; boundary=\"" + boundary
                + "\"; type=\"application/json\"";
        return new Encoded(contentType, body);
    }

    private static byte[] buildBody(String boundary, Object jsonRoot,
                                    String contentId, byte[] tpduBinary) {
        byte[] dash = "--".getBytes(StandardCharsets.US_ASCII);
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.US_ASCII);
        byte[] jsonHeader = ("Content-Type: " + CONTENT_TYPE_JSON + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] jsonBody = SdkConverter.valueAsString(jsonRoot).getBytes(StandardCharsets.UTF_8);
        byte[] binaryHeader = ("Content-Type: " + CONTENT_TYPE_VND_3GPP_SMS + "\r\n"
                + "Content-Id: " + contentId + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(dash);
            out.write(boundaryBytes);
            out.write(CRLF);
            out.write(jsonHeader);
            out.write(jsonBody);
            out.write(CRLF);
            out.write(dash);
            out.write(boundaryBytes);
            out.write(CRLF);
            out.write(binaryHeader);
            out.write(tpduBinary);
            out.write(CRLF);
            out.write(dash);
            out.write(boundaryBytes);
            out.write(dash);
            out.write(CRLF);
        } catch (Exception e) {
            throw new SdkException(ErrorCodes.INTERNAL_SERIALIZATION,
                    "Failed to encode multipart/related body", e.getMessage());
        }
        return out.toByteArray();
    }

    public record Encoded(String contentType, byte[] body) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Encoded(String type, byte[] body1))) return false;
            return java.util.Objects.equals(contentType, type)
                    && java.util.Arrays.equals(body, body1);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hashCode(contentType) + java.util.Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "Encoded[contentType=" + contentType
                    + ", body=" + java.util.Arrays.toString(body) + "]";
        }
    }
}
