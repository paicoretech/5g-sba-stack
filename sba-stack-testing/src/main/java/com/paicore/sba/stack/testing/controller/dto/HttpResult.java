package com.paicore.sba.stack.testing.controller.dto;

import com.paicore.sba.stack.utils.SdkConverter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-neutral response from a controller. The mock server (Netty
 * HTTP/2) or any other host adapts this into its own response type
 * — controllers don't depend on Netty, the JDK HTTP server, or Spring.
 */
public record HttpResult(int status, Map<String, String> headers, byte[] body) {

    public static HttpResult ok(Object body) { return json(200, body); }
    public static HttpResult created(Object body) { return json(201, body); }
    public static HttpResult accepted(Object body) { return json(202, body); }

    public static HttpResult json(int status, Object body) {
        byte[] bytes = SdkConverter.valueAsString(body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("content-length", String.valueOf(bytes.length));
        return new HttpResult(status, headers, bytes);
    }

    public static HttpResult problem(int status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://sba-stack.local/errors/" + code);
        body.put("title", code);
        body.put("status", status);
        body.put("detail", detail == null ? "" : detail);
        byte[] bytes = SdkConverter.valueAsString(body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/problem+json");
        headers.put("content-length", String.valueOf(bytes.length));
        return new HttpResult(status, headers, bytes);
    }

    public static HttpResult unauthorized(String detail) { return problem(401, "SMSF-4010", detail); }
    public static HttpResult badRequest(String code, String detail) { return problem(400, code, detail); }
    public static HttpResult notFound(String code, String detail) { return problem(404, code, detail); }
    public static HttpResult methodNotAllowed(String detail) { return problem(405, "MOCK-4050", detail); }
    public static HttpResult serverError(String detail) { return problem(500, "MOCK-5000", detail); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HttpResult(int otherStatus, Map<String, String> otherHeaders, byte[] otherBody))) return false;
        return status == otherStatus
                && Objects.equals(headers, otherHeaders)
                && Arrays.equals(body, otherBody);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(status, headers) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "HttpResult[status=" + status + ", headers=" + headers
                + ", body=" + Arrays.toString(body) + "]";
    }
}
