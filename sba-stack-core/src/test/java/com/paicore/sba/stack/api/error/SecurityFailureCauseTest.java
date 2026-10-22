package com.paicore.sba.stack.api.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityFailureCauseTest {

    @Test
    @DisplayName("HTTP statuses align with RFC 6750 / RFC 7807")
    void httpStatusMapping() {
        assertThat(SecurityFailureCause.MISSING_AUTHORIZATION_HEADER.httpStatus()).isEqualTo(401);
        assertThat(SecurityFailureCause.INVALID_TOKEN.httpStatus()).isEqualTo(401);
        assertThat(SecurityFailureCause.TOKEN_EXPIRED.httpStatus()).isEqualTo(401);
        assertThat(SecurityFailureCause.INVALID_SCOPE.httpStatus()).isEqualTo(403);
        assertThat(SecurityFailureCause.JWKS_UNAVAILABLE.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("MISSING_AUTHORIZATION_HEADER renders Bearer challenge with realm")
    void missingHeaderRendersRealm() {
        String challenge = SecurityFailureCause.MISSING_AUTHORIZATION_HEADER.wwwAuthenticate("smsf");
        assertThat(challenge).isEqualTo("Bearer realm=\"smsf\"");
    }

    @Test
    @DisplayName("MISSING_AUTHORIZATION_HEADER tolerates null realm")
    void missingHeaderTolerantToNullRealm() {
        String challenge = SecurityFailureCause.MISSING_AUTHORIZATION_HEADER.wwwAuthenticate(null);
        assertThat(challenge).isEqualTo("Bearer realm=\"\"");
    }

    @Test
    @DisplayName("INVALID_TOKEN family returns RFC 6750 invalid_token error")
    void invalidTokenChallenge() {
        assertThat(SecurityFailureCause.INVALID_TOKEN.wwwAuthenticate())
                .isEqualTo(SecurityFailureCause.BEARER_INVALID_TOKEN);
        assertThat(SecurityFailureCause.TOKEN_EXPIRED.wwwAuthenticate())
                .isEqualTo(SecurityFailureCause.BEARER_INVALID_TOKEN);
        assertThat(SecurityFailureCause.ISSUER_MISMATCH.wwwAuthenticate())
                .isEqualTo(SecurityFailureCause.BEARER_INVALID_TOKEN);
    }

    @Test
    @DisplayName("INVALID_SCOPE emits insufficient_scope per RFC 6750 §3.1")
    void insufficientScopeChallenge() {
        assertThat(SecurityFailureCause.INVALID_SCOPE.wwwAuthenticate())
                .isEqualTo("Bearer error=\"insufficient_scope\"");
    }

    @Test
    @DisplayName("JWKS_UNAVAILABLE has no Bearer challenge")
    void jwksUnavailableHasNoChallenge() {
        assertThat(SecurityFailureCause.JWKS_UNAVAILABLE.wwwAuthenticate()).isNull();
    }

    @Test
    @DisplayName("problemTypeUri uses kebab-cased enum name under base")
    void problemTypeUri() {
        assertThat(SecurityFailureCause.TOKEN_EXPIRED.problemTypeUri("https://errors.local"))
                .isEqualTo("https://errors.local/token-expired");
        assertThat(SecurityFailureCause.MISSING_AUTHORIZATION_HEADER.problemTypeUri("https://errors.local/"))
                .isEqualTo("https://errors.local/missing-authorization-header");
    }

    @Test
    @DisplayName("problemTypeUri tolerates null base")
    void problemTypeUriNullBase() {
        assertThat(SecurityFailureCause.INVALID_TOKEN.problemTypeUri(null)).isEqualTo("invalid-token");
    }

    @ParameterizedTest
    @EnumSource(SecurityFailureCause.class)
    @DisplayName("every cause produces a stable lower-kebab URI suffix")
    void everyCauseHasStableSuffix(SecurityFailureCause cause) {
        String uri = cause.problemTypeUri("urn:test");
        assertThat(uri).matches("urn:test/[a-z\\-]+");
    }
}
