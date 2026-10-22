package com.paicore.sba.stack.mo;

import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.utils.ErrorCodes;
import com.paicore.sba.stack.utils.SdkConverter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a {@code multipart/related} body (3GPP TS29540 Nsmsf_SMService) into
 * its JSON root part and the referenced binary SMS part. Implementation is
 * intentionally dependency-free: we rely on byte boundaries per RFC 2046 §5.1
 * and parse header lines with a simple state machine.
 */
public final class MultipartRelatedDecoder {

    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^;\"\\s]+)\"?", Pattern.CASE_INSENSITIVE);

    private static final String CONTENT_TYPE = "content-type";
    private static final String CONTENT_ID = "content-id";
    private static final String INVALID_MO_PAYLOAD = "Invalid MO payload";

    private MultipartRelatedDecoder() {
        throw new IllegalStateException("Utility class");
    }

    public static MoMultipartPayload decode(byte[] body, String contentTypeHeader) {
        RawMultipartPayload raw = decodeRaw(body, contentTypeHeader);
        SmsRecordData jsonRecord = SdkConverter.stringToObject(raw.json(), SmsRecordData.class);
        String contentId = jsonRecord.getSmsPayload() == null ? null : jsonRecord.getSmsPayload().getContentId();

        return MoMultipartPayload.builder()
                .contentId(contentId)
                .jsonData(jsonRecord)
                .binaryData(binaryOf(body, contentTypeHeader, contentId))
                .build();
    }

    /**
     * Splits the body without assuming what the JSON root is, so a caller can parse a root this
     * class does not know about. The binary part is the first non-JSON one, or the one the given
     * content id points at.
     */
    public static RawMultipartPayload decodeRaw(byte[] body, String contentTypeHeader) {
        if (body == null || body.length == 0) {
            throw new SdkException(ErrorCodes.INVALID_MO_PAYLOAD,
                    INVALID_MO_PAYLOAD, "body is empty");
        }
        String boundary = extractBoundary(contentTypeHeader);
        List<Part> parts = splitParts(body, boundary);
        if (parts.isEmpty()) {
            throw new SdkException(ErrorCodes.INVALID_MO_PAYLOAD,
                    INVALID_MO_PAYLOAD, "multipart has no parts");
        }
        Part jsonPart = parts.stream()
                .filter(p -> isJson(p.contentType))
                .findFirst()
                .orElseThrow(() -> new SdkException(ErrorCodes.INVALID_MO_PAYLOAD,
                        INVALID_MO_PAYLOAD, "missing application/json root part"));

        Part binaryPart = parts.stream()
                .filter(p -> !isJson(p.contentType))
                .findFirst()
                .orElse(null);

        return new RawMultipartPayload(
                binaryPart == null ? null : binaryPart.contentId,
                new String(jsonPart.body, StandardCharsets.UTF_8),
                binaryPart == null ? null : binaryPart.body);
    }

    private static byte[] binaryOf(byte[] body, String contentTypeHeader, String contentId) {
        List<Part> parts = splitParts(body, extractBoundary(contentTypeHeader));
        return parts.stream()
                .filter(p -> !isJson(p.contentType))
                .filter(p -> contentId == null || contentId.equals(p.contentId))
                .findFirst()
                .map(p -> p.body)
                .orElse(null);
    }

    static String extractBoundary(String contentTypeHeader) {
        if (contentTypeHeader == null || contentTypeHeader.isBlank()) {
            throw new SdkException(ErrorCodes.INVALID_MO_PAYLOAD,
                    INVALID_MO_PAYLOAD, "Content-Type header is required");
        }
        Matcher m = BOUNDARY_PATTERN.matcher(contentTypeHeader);
        if (!m.find()) {
            throw new SdkException(ErrorCodes.INVALID_MO_PAYLOAD,
                    INVALID_MO_PAYLOAD, "missing boundary parameter in Content-Type");
        }
        return m.group(1);
    }

    private static List<Part> splitParts(byte[] body, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        List<Integer> markers = findAll(body, delimiter);
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < markers.size() - 1; i++) {
            int start = markers.get(i) + delimiter.length;
            int end = markers.get(i + 1);
            // skip optional CRLF right after the boundary
            if (start + 1 < end && body[start] == '\r' && body[start + 1] == '\n') {
                start += 2;
            }
            // strip trailing CRLF before next boundary
            int contentEnd = end;
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            if (contentEnd <= start) {
                continue;
            }
            parts.add(parsePart(body, start, contentEnd));
        }
        return parts;
    }

    private static Part parsePart(byte[] body, int start, int end) {
        int headerEnd = indexOf(body, start, end, new byte[]{'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) {
            return new Part(Map.of(), null, null, new byte[0]);
        }
        String headerBlock = new String(body, start, headerEnd - start, StandardCharsets.US_ASCII);
        Map<String, String> headers = parseHeaders(headerBlock);
        byte[] content = new byte[end - (headerEnd + 4)];
        System.arraycopy(body, headerEnd + 4, content, 0, content.length);
        return new Part(headers,
                headers.get(CONTENT_TYPE),
                stripAngles(headers.get(CONTENT_ID)),
                content);
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> headers = new HashMap<>();
        for (String line : block.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String stripAngles(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private static List<Integer> findAll(byte[] haystack, byte[] needle) {
        List<Integer> hits = new ArrayList<>();
        int i = 0;
        while (i <= haystack.length - needle.length) {
            int idx = indexOf(haystack, i, haystack.length, needle);
            if (idx < 0) {
                break;
            }
            hits.add(idx);
            i = idx + needle.length;
        }
        return hits;
    }

    private static int indexOf(byte[] haystack, int from, int until, byte[] needle) {
        for (int i = from; i <= until - needle.length; i++) {
            if (matchesAt(haystack, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    private record Part(Map<String, String> headers, String contentType, String contentId, byte[] body) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Part other)) return false;
            return java.util.Objects.equals(headers, other.headers)
                    && java.util.Objects.equals(contentType, other.contentType)
                    && java.util.Objects.equals(contentId, other.contentId)
                    && java.util.Arrays.equals(body, other.body);
        }
        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hash(headers, contentType, contentId)
                    + java.util.Arrays.hashCode(body);
        }
        @Override
        public String toString() {
            return "Part[headers=" + headers + ", contentType=" + contentType
                    + ", contentId=" + contentId
                    + ", body=" + java.util.Arrays.toString(body) + "]";
        }
    }
}
