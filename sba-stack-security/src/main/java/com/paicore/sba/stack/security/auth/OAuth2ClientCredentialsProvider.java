package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Optional kit — requires nimbus oauth2-oidc-sdk on the classpath. Fetches
 * client_credentials access tokens and caches them until near-expiry.
 * The HTTP fetcher is injected so we can test without real network calls.
 */
@RequiredArgsConstructor
public class OAuth2ClientCredentialsProvider {

    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final Function<TokenRequest, TokenResponse> fetcher;

    private volatile String currentToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public synchronized String acquireToken() {
        Instant now = Instant.now();
        if (currentToken != null && now.isBefore(expiresAt.minusSeconds(30))) {
            return currentToken;
        }
        TokenResponse response = fetcher.apply(new TokenRequest(tokenEndpoint, clientId, clientSecret));
        if (response == null || response.accessToken == null) {
            throw new AuthenticationException("OAuth2 token endpoint returned empty token");
        }
        this.currentToken = response.accessToken;
        this.expiresAt = now.plus(response.validity);
        return currentToken;
    }

    public record TokenRequest(URI endpoint, String clientId, String clientSecret) {}

    public record TokenResponse(String accessToken, Duration validity) {}
}
