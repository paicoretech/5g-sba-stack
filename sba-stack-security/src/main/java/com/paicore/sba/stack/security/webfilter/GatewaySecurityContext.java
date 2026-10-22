package com.paicore.sba.stack.security.webfilter;

public interface GatewaySecurityContext {

    int networkId();

    boolean isOauth2Enabled();

    boolean isRequireMtls();

    String expectedPeerCertificatePath();
}
