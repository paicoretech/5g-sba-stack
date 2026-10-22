package com.paicore.sba.stack.security.webfilter;

import com.paicore.sba.stack.security.auth.Authenticator;

import java.util.Optional;

public interface GatewaySecurityLookup {

    Optional<GatewaySecurityContext> findByNetworkId(int networkId);

    Optional<Authenticator> authenticatorForNetworkId(int networkId);
}
