package com.paicore.sba.stack.spi;

/**
 * SPI implemented by sba-stack-security to authenticate inbound requests.
 * Concrete credential types live in the security kit; this contract stays
 * opaque so core has no auth-stack deps.
 */
public interface SecurityProvider {

    boolean authenticate(String credential);
}
