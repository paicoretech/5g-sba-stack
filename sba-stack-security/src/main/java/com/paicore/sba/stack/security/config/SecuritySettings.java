package com.paicore.sba.stack.security.config;

import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.security.auth.ApiKeyAuthenticator;
import com.paicore.sba.stack.security.auth.Authenticator;
import com.paicore.sba.stack.security.auth.JwtAuthenticator;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Generated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Generated
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecuritySettings implements SecurityConfig {

    private static final String BEARER_SCHEME = "Bearer";
    private static final String API_KEY_SCHEME = "ApiKey";

    @Builder.Default
    private TlsConfig tls = TlsConfig.builder().build();

    private String apiKey;
    private String jwtSecret;
    private String jwtIssuer;
    private String oauth2TokenEndpoint;
    private String oauth2ClientId;
    private String oauth2ClientSecret;

    @Override
    public boolean isAuthRequired() {
        return isSet(jwtSecret) || isSet(apiKey);
    }

    @Override
    public String getAuthScheme() {
        return isSet(jwtSecret) ? BEARER_SCHEME : API_KEY_SCHEME;
    }

    @Override
    public boolean isMtlsRequired() {
        return tls != null && tls.isMTlsRequired();
    }

    @Override
    public boolean authenticate(String credential) {
        Authenticator auth = pickAuthenticator();
        if (auth == null) {
            return true;
        }
        try {
            auth.authenticate(credential);
            return true;
        } catch (AuthenticationException e) {
            return false;
        }
    }

    private Authenticator pickAuthenticator() {
        if (isSet(jwtSecret)) {
            return JwtAuthenticator.withSharedSecret(jwtSecret, jwtIssuer);
        }
        if (isSet(apiKey)) {
            return new ApiKeyAuthenticator(apiKey);
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
