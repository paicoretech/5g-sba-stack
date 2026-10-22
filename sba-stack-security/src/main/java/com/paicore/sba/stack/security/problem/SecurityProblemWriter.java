package com.paicore.sba.stack.security.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicore.sba.stack.api.error.ProblemDetailsResponse;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

@Slf4j
public class SecurityProblemWriter {

    private static final String DEFAULT_CONTENT_TYPE_PROBLEM_JSON = "application/problem+json";

    private final ObjectMapper objectMapper;
    private final String problemTypeBase;
    private final String wwwAuthenticateRealm;
    private final String contentTypeProblemJson;

    public SecurityProblemWriter(ObjectMapper objectMapper,
                                 String problemTypeBase,
                                 String wwwAuthenticateRealm,
                                 String contentTypeProblemJson) {
        this.objectMapper = objectMapper;
        this.problemTypeBase = problemTypeBase;
        this.wwwAuthenticateRealm = wwwAuthenticateRealm;
        this.contentTypeProblemJson = Objects.isNull(contentTypeProblemJson) || contentTypeProblemJson.isBlank()
                ? DEFAULT_CONTENT_TYPE_PROBLEM_JSON : contentTypeProblemJson;
    }

    public SecurityProblemWriter(ObjectMapper objectMapper,
                                 String problemTypeBase,
                                 String wwwAuthenticateRealm) {
        this(objectMapper, problemTypeBase, wwwAuthenticateRealm, DEFAULT_CONTENT_TYPE_PROBLEM_JSON);
    }

    public Mono<Void> write(ServerWebExchange exchange, SecurityFailureCause cause, String detail) {
        String path = exchange.getRequest().getPath().value();
        HttpStatus httpStatus = HttpStatus.valueOf(cause.httpStatus());
        ProblemDetailsResponse body = new ProblemDetailsResponse(
                cause.problemTypeUri(problemTypeBase),
                httpStatus.getReasonPhrase(),
                httpStatus.value(),
                detail,
                path,
                cause.name(),
                Instant.now().toString());

        exchange.getResponse().setStatusCode(httpStatus);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setContentType(MediaType.parseMediaType(contentTypeProblemJson));
        headers.setCacheControl("no-store");
        String wwwAuth = cause.wwwAuthenticate(wwwAuthenticateRealm);
        if (Objects.nonNull(wwwAuth) && !wwwAuth.isBlank()) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, wwwAuth);
        }

        byte[] payload = serialize(body);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(payload);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private byte[] serialize(ProblemDetailsResponse body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            log.error("problem.write.failure cause={} error={}", body.cause(), ex.getMessage());
            return ("{\"status\":" + body.status() + ",\"cause\":\"" + body.cause() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
