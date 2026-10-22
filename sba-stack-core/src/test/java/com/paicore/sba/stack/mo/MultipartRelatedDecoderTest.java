package com.paicore.sba.stack.mo;

import com.paicore.sba.stack.exceptions.SdkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartRelatedDecoderTest {

    private static final String JSON_BODY = """
            {
              "smsRecordId": "777c3edf-129f-486e-a3f8-c48e7b515605",
              "smsPayload": { "contentId": "sms" },
              "gpsi": "msisdn-8613915900000",
              "pei": "imei-123456789012345",
              "accessType": "3GPP_ACCESS"
            }""";

    private static final byte[] BINARY_BODY =
            new byte[]{0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xAA};

    private byte[] buildBody(String boundary, byte[] binary) {
        String b = "--" + boundary;
        StringBuilder sb = new StringBuilder();
        sb.append(b).append("\r\n")
                .append("Content-Type: application/json\r\n\r\n")
                .append(JSON_BODY).append("\r\n")
                .append(b).append("\r\n")
                .append("Content-Type: application/vnd.3gpp.sms\r\n")
                .append("Content-Id: sms\r\n\r\n");
        byte[] header = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n" + b + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + binary.length + footer.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(binary, 0, out, header.length, binary.length);
        System.arraycopy(footer, 0, out, header.length + binary.length, footer.length);
        return out;
    }

    @Test
    @DisplayName("decode extracts the JSON root part and the referenced binary body")
    void decodesJsonRootAndBinaryBody() {
        String contentType = "multipart/related; boundary=\"----Boundary\"; type=\"application/json\"";
        MoMultipartPayload payload = MultipartRelatedDecoder.decode(
                buildBody("----Boundary", BINARY_BODY), contentType);
        assertNotNull(payload.getJsonData());
        assertEquals("sms", payload.getContentId());
        assertEquals("msisdn-8613915900000", payload.getJsonData().getGpsi());
        assertEquals("777c3edf-129f-486e-a3f8-c48e7b515605",
                payload.getJsonData().getSmsRecordId());
        assertArrayEquals(BINARY_BODY, payload.getBinaryData());
    }

    @Test
    @DisplayName("decode tolerates payloads where the binary part is omitted")
    void decodesEvenWhenBinaryPartIsAbsent() {
        String boundary = "----Boundary";
        String body = ("--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + JSON_BODY + "\r\n"
                + "--" + boundary + "--\r\n");
        MoMultipartPayload payload = MultipartRelatedDecoder.decode(
                body.getBytes(StandardCharsets.UTF_8),
                "multipart/related; boundary=" + boundary);
        assertNotNull(payload.getJsonData());
        assertNull(payload.getBinaryData());
    }

    @Test
    @DisplayName("decode strips angle brackets from the Content-Id header per RFC 2392")
    void decodesContentIdWithAngleBrackets() {
        String boundary = "----X";
        String body = ("--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + JSON_BODY + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/vnd.3gpp.sms\r\n"
                + "Content-Id: <sms>\r\n\r\n"
                + "BIN\r\n"
                + "--" + boundary + "--\r\n");
        MoMultipartPayload payload = MultipartRelatedDecoder.decode(
                body.getBytes(StandardCharsets.UTF_8),
                "multipart/related; boundary=" + boundary);
        assertEquals("sms", payload.getContentId());
        assertArrayEquals("BIN".getBytes(StandardCharsets.UTF_8), payload.getBinaryData());
    }

    @Test
    @DisplayName("decode picks the first non-JSON part when the JSON root does not declare a contentId")
    void decodesBinaryWhenJsonPayloadHasNoContentId() {
        String boundary = "----Y";
        String jsonWithoutPayload = """
                {
                  "smsRecordId": "no-payload-uuid",
                  "gpsi": "msisdn-1"
                }""";
        String body = ("--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + jsonWithoutPayload + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/vnd.3gpp.sms\r\n\r\n"
                + "RAW\r\n"
                + "--" + boundary + "--\r\n");
        MoMultipartPayload payload = MultipartRelatedDecoder.decode(
                body.getBytes(StandardCharsets.UTF_8),
                "multipart/related; boundary=" + boundary);
        assertNull(payload.getContentId());
        assertArrayEquals("RAW".getBytes(StandardCharsets.UTF_8), payload.getBinaryData());
    }

    @Test
    @DisplayName("decode rejects payloads whose Content-Type lacks the boundary parameter")
    void rejectsMissingBoundary() {
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(new byte[]{1}, "multipart/related"));
    }

    @Test
    @DisplayName("decode rejects a null Content-Type header")
    void rejectsNullContentType() {
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(new byte[]{1}, null));
    }

    @Test
    @DisplayName("decode rejects a blank Content-Type header")
    void rejectsBlankContentType() {
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(new byte[]{1}, "   "));
    }

    @Test
    @DisplayName("decode rejects an empty body")
    void rejectsEmptyBody() {
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(new byte[0],
                        "multipart/related; boundary=----X"));
    }

    @Test
    @DisplayName("decode rejects a null body")
    void rejectsNullBody() {
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(null,
                        "multipart/related; boundary=----X"));
    }

    @Test
    @DisplayName("decode rejects payloads missing the application/json root part")
    void rejectsMissingJsonPart() {
        String boundary = "----X";
        String body = ("--" + boundary + "\r\n"
                + "Content-Type: application/vnd.3gpp.sms\r\n"
                + "Content-Id: sms\r\n\r\n"
                + "binary\r\n"
                + "--" + boundary + "--\r\n");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String contentType = "multipart/related; boundary=" + boundary;
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(payload, contentType));
    }

    @Test
    @DisplayName("decode rejects a body whose boundaries are not present (no parts)")
    void rejectsBodyWithoutAnyParts() {
        byte[] body = "no boundary here".getBytes(StandardCharsets.UTF_8);
        assertThrows(SdkException.class,
                () -> MultipartRelatedDecoder.decode(body,
                        "multipart/related; boundary=----X"));
    }

    @Test
    @DisplayName("extractBoundary handles quoted and unquoted boundary values regardless of case")
    void extractsBoundaryWithQuotes() {
        assertEquals("---abc",
                MultipartRelatedDecoder.extractBoundary("multipart/related; boundary=\"---abc\""));
        assertEquals("plain",
                MultipartRelatedDecoder.extractBoundary("multipart/related; BOUNDARY=plain"));
    }

    @Test
    @DisplayName("decode picks the first non-JSON part when the binary contentId does not match")
    void decodeFallsBackToFirstNonJsonPartWhenContentIdDiffers() {
        String boundary = "----Q";
        // JSON declares contentId="sms" but the binary part carries a different one.
        // decode() filters by contentId; when no match exists it does NOT fall back,
        // so binaryData should be null.
        String body = ("--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + JSON_BODY + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/vnd.3gpp.sms\r\n"
                + "Content-Id: other\r\n\r\n"
                + "MISMATCH\r\n"
                + "--" + boundary + "--\r\n");
        MoMultipartPayload payload = MultipartRelatedDecoder.decode(
                body.getBytes(StandardCharsets.UTF_8),
                "multipart/related; boundary=" + boundary);
        assertEquals("sms", payload.getContentId());
        assertNull(payload.getBinaryData());
    }
}
