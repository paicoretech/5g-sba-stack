package com.paicore.sba.stack.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("serialises all populated fields")
    void serialisesAllFields() throws Exception {
        ProblemDetailsResponse pd = new ProblemDetailsResponse(
                "https://errors.local/invalid-token",
                "Invalid token",
                401,
                "Token expired",
                "/nsmsf-sms/v2/ue-contexts/imsi-001",
                "TOKEN_EXPIRED",
                "2026-05-19T20:00:00Z");

        String json = mapper.writeValueAsString(pd);

        assertThat(json)
                .contains("\"type\":\"https://errors.local/invalid-token\"")
                .contains("\"title\":\"Invalid token\"")
                .contains("\"status\":401")
                .contains("\"detail\":\"Token expired\"")
                .contains("\"instance\":\"/nsmsf-sms/v2/ue-contexts/imsi-001\"")
                .contains("\"cause\":\"TOKEN_EXPIRED\"")
                .contains("\"timestamp\":\"2026-05-19T20:00:00Z\"");
    }

    @Test
    @DisplayName("omits null fields from the wire format")
    void omitsNullFields() throws Exception {
        ProblemDetailsResponse pd = new ProblemDetailsResponse(
                "https://errors.local/invalid-token",
                "Invalid token",
                401,
                null, null, null, null);

        String json = mapper.writeValueAsString(pd);

        assertThat(json)
                .doesNotContain("detail")
                .doesNotContain("instance")
                .doesNotContain("cause")
                .doesNotContain("timestamp");
    }

    @Test
    @DisplayName("round-trips through Jackson")
    void roundTrips() throws Exception {
        ProblemDetailsResponse original = new ProblemDetailsResponse(
                "https://errors.local/insufficient-scope",
                "Forbidden",
                403,
                "scope nsmsf-sms required",
                null,
                "INVALID_SCOPE",
                null);

        String json = mapper.writeValueAsString(original);
        ProblemDetailsResponse parsed = mapper.readValue(json, ProblemDetailsResponse.class);

        assertThat(parsed).isEqualTo(original);
    }
}
