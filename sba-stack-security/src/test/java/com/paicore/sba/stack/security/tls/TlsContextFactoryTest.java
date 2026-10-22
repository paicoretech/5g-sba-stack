package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.security.config.TlsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TlsContextFactoryTest {

    @TempDir
    Path tempDir;

    private Path keyStorePath;
    private Path trustStorePath;

    @BeforeEach
    void setup() throws Exception {
        keyStorePath = writeEmptyKeyStore(tempDir.resolve("ks.p12"));
        trustStorePath = writeEmptyKeyStore(tempDir.resolve("ts.p12"));
    }

    private Path writeEmptyKeyStore(Path target) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, "changeit".toCharArray());
        try (OutputStream out = Files.newOutputStream(target)) {
            ks.store(out, "changeit".toCharArray());
        }
        return target;
    }

    @Test
    @DisplayName("create builds an SSLContext with default protocol when no keystores are configured")
    void createShouldBuildContextWithoutKeyStores() {
        TlsConfig config = TlsConfig.builder().build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
        assertEquals("TLSv1.3", ctx.getProtocol());
    }

    @Test
    @DisplayName("create propagates SdkException when the configured keyStorePath is missing")
    void createShouldPropagateMissingKeyStorePath() {
        TlsConfig config = TlsConfig.builder().keyStorePath("/nonexistent/path.p12").build();
        TlsContextFactory factory = new TlsContextFactory(config);
        assertThrows(SdkException.class, factory::create);
    }

    @Test
    @DisplayName("create initializes a KeyManagerFactory when keyStorePath is provided")
    void createShouldInitializeKeyManagerFactoryWhenKeyStoreProvided() {
        TlsConfig config = TlsConfig.builder()
                .keyStorePath(keyStorePath.toString())
                .keyStorePassword("changeit")
                .keyStoreType("PKCS12")
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
    }

    @Test
    @DisplayName("create initializes a TrustManagerFactory when trustStorePath is provided")
    void createShouldInitializeTrustManagerFactoryWhenTrustStoreProvided() {
        TlsConfig config = TlsConfig.builder()
                .trustStorePath(trustStorePath.toString())
                .trustStorePassword("changeit")
                .trustStoreType("PKCS12")
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
    }

    @Test
    @DisplayName("create initializes both factories when keyStore and trustStore are provided")
    void createShouldInitializeBothFactoriesWhenConfigured() {
        TlsConfig config = TlsConfig.builder()
                .keyStorePath(keyStorePath.toString())
                .keyStorePassword("changeit")
                .keyStoreType("PKCS12")
                .trustStorePath(trustStorePath.toString())
                .trustStorePassword("changeit")
                .trustStoreType("PKCS12")
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
    }

    @Test
    @DisplayName("create falls back to TLS when the protocols list is empty")
    void createShouldFallBackToGenericTlsWhenProtocolsEmpty() {
        TlsConfig config = TlsConfig.builder()
                .protocols(Collections.emptyList())
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
        assertEquals("TLS", ctx.getProtocol());
    }

    @Test
    @DisplayName("create falls back to TLS when the protocols list is null")
    void createShouldFallBackToGenericTlsWhenProtocolsNull() {
        TlsConfig config = TlsConfig.builder()
                .protocols(null)
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertNotNull(ctx);
        assertEquals("TLS", ctx.getProtocol());
    }

    @Test
    @DisplayName("create uses the first protocol from the configured list")
    void createShouldUseFirstConfiguredProtocol() {
        TlsConfig config = TlsConfig.builder()
                .protocols(List.of("TLSv1.2"))
                .build();
        SSLContext ctx = new TlsContextFactory(config).create();
        assertEquals("TLSv1.2", ctx.getProtocol());
    }

    @Test
    @DisplayName("create wraps unexpected failures as SdkException")
    void createShouldWrapUnexpectedFailuresAsSdkException() {
        // Force an unsupported SSLContext protocol — JDK throws NoSuchAlgorithmException,
        // which is mapped to SdkException via the generic catch.
        TlsConfig config = TlsConfig.builder()
                .protocols(List.of("INVALID-PROTOCOL"))
                .build();
        TlsContextFactory factory = new TlsContextFactory(config);
        assertThrows(SdkException.class, factory::create);
    }

    @Test
    @DisplayName("mTlsRequired flag is part of the config contract")
    void tlsConfigExposesMtlsFlag() {
        TlsConfig config = TlsConfig.builder().mTlsRequired(true).build();
        org.junit.jupiter.api.Assertions.assertTrue(config.isMTlsRequired());
    }
}
