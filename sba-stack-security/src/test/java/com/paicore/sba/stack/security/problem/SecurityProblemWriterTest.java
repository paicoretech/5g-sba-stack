package com.paicore.sba.stack.security.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityProblemWriterTest {

    private static final String PROBLEM_TYPE_BASE = "https://errors.paicbd.com/sba/";
    private static final String REALM = "smsf";
    private static final String PATH = "/nsmsf-sms/v2/ue-contexts/abc";

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("INVALID_TOKEN: writes 401 with problem+json body containing RFC 7807 fields")
    void write_ShouldEmitProblemJsonWithRfc7807FieldsForInvalidToken() throws Exception {
        SecurityProblemWriter writer = new SecurityProblemWriter(objectMapper, PROBLEM_TYPE_BASE, REALM);
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange, SecurityFailureCause.INVALID_TOKEN, "bad signature"))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getContentType()).isNotNull();
        assertThat(headers.getContentType().toString()).startsWith("application/problem+json");
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");

        JsonNode body = readBody(exchange);
        assertThat(body.get("type").asText()).isEqualTo(PROBLEM_TYPE_BASE + "invalid-token");
        assertThat(body.get("title").asText()).isEqualTo(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("detail").asText()).isEqualTo("bad signature");
        assertThat(body.get("instance").asText()).isEqualTo(PATH);
        assertThat(body.get("cause").asText()).isEqualTo("INVALID_TOKEN");
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    @Test
    @DisplayName("INVALID_SCOPE maps to HTTP 403 with insufficient_scope challenge")
    void write_ShouldEmit403ForInvalidScope() throws Exception {
        SecurityProblemWriter writer = new SecurityProblemWriter(objectMapper, PROBLEM_TYPE_BASE, REALM);
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange, SecurityFailureCause.INVALID_SCOPE, "missing nsmsf-sms"))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        JsonNode body = readBody(exchange);
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("cause").asText()).isEqualTo("INVALID_SCOPE");
    }

    @Test
    @DisplayName("MISSING_AUTHORIZATION_HEADER substitutes the realm into the Bearer challenge")
    void write_ShouldUseRealmInChallengeForMissingHeader() {
        SecurityProblemWriter writer = new SecurityProblemWriter(objectMapper, PROBLEM_TYPE_BASE, REALM);
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange,
                SecurityFailureCause.MISSING_AUTHORIZATION_HEADER, "no auth header"))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"" + REALM + "\"");
    }

    @Test
    @DisplayName("JWKS_UNAVAILABLE omits the WWW-Authenticate header and returns HTTP 503")
    void write_ShouldOmitWwwAuthenticateForJwksUnavailable() {
        SecurityProblemWriter writer = new SecurityProblemWriter(objectMapper, PROBLEM_TYPE_BASE, REALM);
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange,
                SecurityFailureCause.JWKS_UNAVAILABLE, "issuer JWKS unreachable"))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    @DisplayName("custom contentType in the 4-arg constructor is propagated to Content-Type header")
    void write_ShouldUseCustomContentTypeWhenConstructorOverridesIt() {
        SecurityProblemWriter writer = new SecurityProblemWriter(
                objectMapper, PROBLEM_TYPE_BASE, REALM, "application/vnd.problem+json");
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange, SecurityFailureCause.INVALID_TOKEN, "x"))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .startsWith("application/vnd.problem+json");
    }

    @Test
    @DisplayName("blank custom contentType falls back to the application/problem+json default")
    void write_ShouldFallBackToDefaultContentTypeWhenBlank() {
        SecurityProblemWriter writer = new SecurityProblemWriter(
                objectMapper, PROBLEM_TYPE_BASE, REALM, "   ");
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange, SecurityFailureCause.INVALID_TOKEN, "x"))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
    }

    @Test
    @DisplayName("when ObjectMapper raises JsonProcessingException, a minimal JSON fallback body is written")
    void write_ShouldFallBackToMinimalJsonWhenObjectMapperFails() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("boom") {});
        SecurityProblemWriter writer = new SecurityProblemWriter(failing, PROBLEM_TYPE_BASE, REALM);
        MockServerWebExchange exchange = newExchange();

        StepVerifier.create(writer.write(exchange, SecurityFailureCause.INVALID_TOKEN, "x"))
                .verifyComplete();

        String body = readRawBody(exchange);
        assertThat(body)
                .contains("\"status\":401")
                .contains("\"cause\":\"INVALID_TOKEN\"");
    }

    private static MockServerWebExchange newExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get(PATH).build());
    }

    private JsonNode readBody(MockServerWebExchange exchange) throws Exception {
        return objectMapper.readTree(readRawBody(exchange));
    }

    private String readRawBody(MockServerWebExchange exchange) {
        MockServerHttpResponse response = exchange.getResponse();
        return response.getBodyAsString().block();
    }
}
