package com.paicore.sba.stack.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Nested
    @DisplayName("disabled() sentinel")
    class DisabledSentinel {

        @Test
        @DisplayName("returns a no-auth, no-mTLS, no-bypass instance")
        void disabledDefaults() {
            SecurityConfig disabled = SecurityConfig.disabled();
            assertThat(disabled.isAuthRequired()).isFalse();
            assertThat(disabled.isMtlsRequired()).isFalse();
            assertThat(disabled.getAuthScheme()).isEqualTo("Bearer");
            assertThat(disabled.getAuthHeaderName()).isEqualTo("Authorization");
            assertThat(disabled.getBypassPaths()).isEmpty();
            assertThat(disabled.isPathBypassed("/anything")).isFalse();
        }

        @Test
        @DisplayName("authorize accepts anything when auth not required")
        void disabledAuthorisesAnything() {
            SecurityConfig disabled = SecurityConfig.disabled();
            assertThat(disabled.authorize(null)).isTrue();
            assertThat(disabled.authorize("garbage")).isTrue();
            assertThat(disabled.authorize("Bearer xyz")).isTrue();
        }
    }

    @Nested
    @DisplayName("bypass path matching")
    class BypassPaths {

        private SecurityConfig configWith(String... patterns) {
            return new SecurityConfig() {
                @Override public boolean isAuthRequired() { return true; }
                @Override public Collection<String> getBypassPaths() { return Arrays.asList(patterns); }
            };
        }

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/health", "/actuator/metrics/jvm.memory.used", "/actuator"})
        @DisplayName("ant /actuator/** matches the prefix and any descendant")
        void wildcardSuffix(String path) {
            assertThat(configWith("/actuator/**").isPathBypassed(path)).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
                "/health,         /health",
                "/info,           /info"
        })
        @DisplayName("exact patterns match their literal path")
        void exactMatch(String pattern, String path) {
            assertThat(configWith(pattern).isPathBypassed(path)).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
                "/health,        /healthcheck",
                "/actuator/**,   /something/else",
                "/api/*,         /api/v1/users"
        })
        @DisplayName("non-matching paths are not bypassed")
        void nonMatch(String pattern, String path) {
            assertThat(configWith(pattern).isPathBypassed(path)).isFalse();
        }

        @Test
        @DisplayName("single-segment * does not cross slashes")
        void singleStarStopsAtSlash() {
            SecurityConfig cfg = configWith("/api/*");
            assertThat(cfg.isPathBypassed("/api/foo")).isTrue();
            assertThat(cfg.isPathBypassed("/api/foo/bar")).isFalse();
        }

        @Test
        @DisplayName("? matches exactly one non-slash char")
        void questionMark() {
            SecurityConfig cfg = configWith("/api/v?");
            assertThat(cfg.isPathBypassed("/api/v1")).isTrue();
            assertThat(cfg.isPathBypassed("/api/v")).isFalse();
            assertThat(cfg.isPathBypassed("/api/v10")).isFalse();
        }

        @Test
        @DisplayName("regex meta-chars in the pattern are escaped")
        void regexMetaEscaped() {
            SecurityConfig cfg = configWith("/api/v1.0");
            assertThat(cfg.isPathBypassed("/api/v1.0")).isTrue();
            assertThat(cfg.isPathBypassed("/api/v1x0")).isFalse();
        }

        @Test
        @DisplayName("blank or null patterns in the list are ignored")
        void blankPatternsIgnored() {
            SecurityConfig cfg = new SecurityConfig() {
                @Override public boolean isAuthRequired() { return true; }
                @Override public Collection<String> getBypassPaths() { return Arrays.asList("", "  ", null, "/health"); }
            };
            assertThat(cfg.isPathBypassed("/health")).isTrue();
            assertThat(cfg.isPathBypassed("/other")).isFalse();
        }

        @Test
        @DisplayName("null path returns false")
        void nullPathReturnsFalse() {
            assertThat(configWith("/**").isPathBypassed(null)).isFalse();
        }

        @Test
        @DisplayName("empty bypass list returns false for any path")
        void emptyListReturnsFalse() {
            SecurityConfig cfg = new SecurityConfig() {
                @Override public boolean isAuthRequired() { return true; }
            };
            assertThat(cfg.isPathBypassed("/anything")).isFalse();
        }
    }

    @Nested
    @DisplayName("authorize header parsing")
    class AuthorizeHeader {

        SecurityConfig requireBearer(boolean accept) {
            return new SecurityConfig() {
                @Override public boolean isAuthRequired() { return true; }
                @Override public boolean authenticate(String credential) {
                    return accept && "valid".equals(credential);
                }
            };
        }

        @Test
        @DisplayName("missing header is rejected when auth required")
        void missingHeader() {
            assertThat(requireBearer(true).authorize(null)).isFalse();
        }

        @Test
        @DisplayName("wrong scheme is rejected")
        void wrongScheme() {
            assertThat(requireBearer(true).authorize("ApiKey abc")).isFalse();
        }

        @Test
        @DisplayName("valid Bearer token is accepted")
        void validBearer() {
            assertThat(requireBearer(true).authorize("Bearer valid")).isTrue();
        }

        @Test
        @DisplayName("Bearer token with surrounding whitespace is trimmed")
        void trimsToken() {
            assertThat(requireBearer(true).authorize("Bearer   valid   ")).isTrue();
        }
    }

    @Nested
    @DisplayName("custom configuration")
    class CustomConfig {

        @Test
        @DisplayName("custom auth header name is reported")
        void customHeaderName() {
            SecurityConfig cfg = new SecurityConfig() {
                @Override public String getAuthHeaderName() { return "X-Service-Token"; }
            };
            assertThat(cfg.getAuthHeaderName()).isEqualTo("X-Service-Token");
        }

        @Test
        @DisplayName("bypass list survives empty implementation")
        void emptyBypassList() {
            SecurityConfig cfg = new SecurityConfig() {};
            assertThat(cfg.getBypassPaths()).isEqualTo(List.of());
        }
    }
}
