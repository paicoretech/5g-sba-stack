package com.paicore.sba.stack.config;

import lombok.AllArgsConstructor;
import lombok.Generated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Shared TLS fields used by every kit that needs a key/trust store and a
 * negotiated protocol list. Concrete configs (the security kit's
 * {@code TlsConfig}, the http2 kit's {@code TlsSettings}) extend this with
 * kit-specific fields (mTLS enforcement, hostname verification, cipher
 * suites, …) without re-declaring the common pieces.
 */
@Getter
@Setter
@Generated
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseTlsConfig {

    private String keyStorePath;
    private String keyStorePassword;
    private String keyPath;

    @lombok.Builder.Default
    private String keyStoreType = "PKCS12";

    private String trustStorePath;
    private String trustStorePassword;

    @lombok.Builder.Default
    private String trustStoreType = "PKCS12";

    @lombok.Builder.Default
    private List<String> protocols = List.of("TLSv1.3", "TLSv1.2");
}
