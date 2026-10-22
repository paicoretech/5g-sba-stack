package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.api.error.SecurityFailureCause;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Routes JWT validation to a per-issuer {@link Authenticator} for multi-tenant boundaries. */
public final class IssuerAuthenticatorRegistry implements Authenticator {

    private final ConcurrentMap<String, Authenticator> byIssuer = new ConcurrentHashMap<>();

    public void register(String issuer, Authenticator authenticator) {
        if (issuer == null || issuer.isBlank() || authenticator == null) {
            return;
        }
        byIssuer.put(issuer, authenticator);
    }

    public void unregister(String issuer) {
        if (issuer == null) {
            return;
        }
        byIssuer.remove(issuer);
    }

    public Optional<Authenticator> forIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byIssuer.get(issuer));
    }

    public int size() {
        return byIssuer.size();
    }

    public Map<String, Authenticator> snapshot() {
        return Map.copyOf(byIssuer);
    }

    public void clear() {
        byIssuer.clear();
    }

    @Override
    public Principal authenticate(String token) throws AuthenticationException {
        String issuer = AsymmetricJwtAuthenticator.peekIssuer(token);
        if (issuer == null) {
            throw new AuthenticationException("Token is missing iss claim",
                    SecurityFailureCause.INVALID_TOKEN);
        }
        Authenticator chosen = byIssuer.get(issuer);
        if (chosen == null) {
            throw new AuthenticationException("Unknown issuer: " + issuer,
                    SecurityFailureCause.UNKNOWN_ISSUER);
        }
        return chosen.authenticate(token);
    }
}
