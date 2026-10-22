package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.transport.http2.NettyHttp2Client;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpJwkSetLoaderTest {

    private static final String VALID_JWKS = """
            {"keys":[{"kty":"RSA","kid":"sample","use":"sig","alg":"RS256",
            "n":"sXch2KO_DfsfA1bWPCO3xL6f9j-Y0wgIkW7AfRfvUF7nbJqJ20Mz4WfDsHRy-OWtZcvWB6mScNI4SmoSiVoTBzPa4XKsRwsAfHcuB80odp0ucIkjMNDc-AYL75YIvO4Mc1jXcM9zBcGcoeeftLAlnELLZCNlk_yYAFYRizq05bIBLHcvIBpDXa_xK17v4ulVtVbThnFI3iv3iD55GsX5nlncDFwxv1WIm-PrJjVOJUKNzh5N6FoUx0YOySf-aDS-zJlGUDikutpHcVuRsmlW7CHfgVPCqHb2GNFPLnB7s27UV-LCEbXVKlbQH0Ll-rJUFmaIZyx9CO27wF6Yqo6V6w",
            "e":"AQAB"}]}
            """;

    private NettyHttp2Client httpClient;
    private Http2ClientSettings settings;

    @BeforeEach
    void setUp() {
        httpClient = mock(NettyHttp2Client.class);
        when(httpClient.isConnected()).thenReturn(true);
        settings = Http2ClientSettings.builder()
                .key("test")
                .host("issuer.local")
                .port(8443)
                .path("/oauth2/jwks")
                .tls(TlsSettings.builder().enabled(false).build())
                .build();
    }

    @Test
    @DisplayName("HTTP 200 with a valid JWKS body returns the parsed JWKSet")
    void loadReturnsParsedJwkSetOnHttp200() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(okResponse(VALID_JWKS)));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);
        JWKSet result = loader.load();

        assertThat(result.getKeyByKeyId("sample")).isNotNull();
    }

    @Test
    @DisplayName("Issued request uses GET and the configured path")
    void loadIssuesGetWithConfiguredPath() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(okResponse(VALID_JWKS)));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);
        loader.load();

        ArgumentCaptor<Http2Request> captor = ArgumentCaptor.forClass(Http2Request.class);
        verify(httpClient).send(captor.capture());
        Http2Request sent = captor.getValue();
        assertThat(sent.getMethod()).isEqualTo("GET");
        assertThat(sent.getPath()).isEqualTo("/oauth2/jwks");
    }

    @Test
    @DisplayName("HTTP 5xx is retried up to maxAttempts and then surfaces as JWKS_UNAVAILABLE")
    void loadRetriesOn5xxAndFailsAfterMaxAttempts() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(errorResponse(503)));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 3, httpClient);

        assertThatThrownBy(loader::load)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> {
                    AuthenticationException ae = (AuthenticationException) ex;
                    assertThat(ae.getFailureCause()).isEqualTo(SecurityFailureCause.JWKS_UNAVAILABLE);
                    assertThat(ae.getDetails()).contains("503");
                });
        verify(httpClient, times(3)).send(any(Http2Request.class));
    }

    @Test
    @DisplayName("Recovery on the second attempt returns successfully after a transient 5xx")
    void loadRecoversOnRetryWhenLaterAttemptSucceeds() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(errorResponse(500)))
                .thenReturn(CompletableFuture.completedFuture(okResponse(VALID_JWKS)));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 3, httpClient);
        JWKSet result = loader.load();

        assertThat(result.getKeyByKeyId("sample")).isNotNull();
        verify(httpClient, times(2)).send(any(Http2Request.class));
    }

    @Test
    @DisplayName("ExecutionException propagates as AuthenticationException with cause set and JWKS_UNAVAILABLE")
    void loadWrapsExecutionExceptionAsAuthenticationException() {
        Throwable boom = new RuntimeException("connection refused");
        CompletableFuture<Http2Response> failed = new CompletableFuture<>();
        failed.completeExceptionally(boom);
        when(httpClient.send(any(Http2Request.class))).thenReturn(failed);

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);

        assertThatThrownBy(loader::load)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> {
                    AuthenticationException ae = (AuthenticationException) ex;
                    assertThat(ae.getFailureCause()).isEqualTo(SecurityFailureCause.JWKS_UNAVAILABLE);
                    assertThat(ae.getCause()).isInstanceOf(ExecutionException.class);
                });
    }

    @Test
    @DisplayName("HTTP 200 with malformed body surfaces as JWKS_UNAVAILABLE")
    void loadFailsWithJwksUnavailableOnMalformedBody() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(okResponse("{ not actually a JWKS }")));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);

        assertThatThrownBy(loader::load)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getFailureCause())
                        .isEqualTo(SecurityFailureCause.JWKS_UNAVAILABLE));
    }

    @Test
    @DisplayName("HTTP 200 with empty body surfaces as JWKS_UNAVAILABLE")
    void loadFailsOnEmptyBody() {
        Http2Response empty = Http2Response.builder().status(200).body(new byte[0]).build();
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(empty));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);

        assertThatThrownBy(loader::load)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("empty body"));
    }

    @Test
    @DisplayName("InterruptedException restores the thread interrupt flag and rethrows as AuthenticationException")
    void loadRestoresInterruptFlagOnInterruptedException() {
        CompletableFuture<Http2Response> future = new CompletableFuture<>();
        when(httpClient.send(any(Http2Request.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return future;
        });

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 1, httpClient);
        Thread.interrupted();

        try {
            assertThatThrownBy(loader::load)
                    .isInstanceOf(AuthenticationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("maxAttempts below 1 is coerced to 1 (single attempt)")
    void constructorCoercesMaxAttemptsBelowOneToOne() {
        when(httpClient.send(any(Http2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(okResponse(VALID_JWKS)));

        HttpJwkSetLoader loader = new HttpJwkSetLoader(settings, 0, httpClient);
        loader.load();

        verify(httpClient, times(1)).send(any(Http2Request.class));
    }

    @Test
    @DisplayName("null settings is rejected by the public constructor")
    void publicConstructorRejectsNullSettings() {
        assertThatThrownBy(() -> new HttpJwkSetLoader(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settings");
    }

    private static Http2Response okResponse(String body) {
        return Http2Response.builder()
                .status(200)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private static Http2Response errorResponse(int status) {
        return Http2Response.builder()
                .status(status)
                .body(new byte[0])
                .build();
    }
}
