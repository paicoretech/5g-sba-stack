package com.paicore.sba.stack.testing.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage tests for the {@link RecordedRequest} Lombok POJO.
 */
class RecordedRequestTest {

    @Test
    @DisplayName("builder populates every field and getters return the same values")
    void builderRoundTrip() {
        Instant now = Instant.now();
        byte[] body = new byte[]{1, 2, 3};
        Map<String, String> headers = Map.of("content-type", "application/json");

        RecordedRequest req = RecordedRequest.builder()
                .method("POST")
                .path("/ue-contexts/imsi-1")
                .query("foo=bar")
                .headers(headers)
                .body(body)
                .receivedAt(now)
                .responseStatus(201)
                .build();

        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/ue-contexts/imsi-1");
        assertThat(req.getQuery()).isEqualTo("foo=bar");
        assertThat(req.getHeaders()).isSameAs(headers);
        assertThat(req.getBody()).isSameAs(body);
        assertThat(req.getReceivedAt()).isEqualTo(now);
        assertThat(req.getResponseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("builder defaults every field to null/zero when omitted")
    void builderDefaults() {
        RecordedRequest req = RecordedRequest.builder().build();

        assertThat(req.getMethod()).isNull();
        assertThat(req.getPath()).isNull();
        assertThat(req.getQuery()).isNull();
        assertThat(req.getHeaders()).isNull();
        assertThat(req.getBody()).isNull();
        assertThat(req.getReceivedAt()).isNull();
        assertThat(req.getResponseStatus()).isZero();
    }
}
