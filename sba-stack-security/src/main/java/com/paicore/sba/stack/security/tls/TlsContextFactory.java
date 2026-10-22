package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.tls.PemKeyStoreLoader;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.security.config.TlsConfig;
import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.RequiredArgsConstructor;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

@RequiredArgsConstructor
public class TlsContextFactory {

    private final TlsConfig config;

    public SSLContext create() {
        try {
            KeyManagerFactory kmf = null;
            if (config.getKeyStorePath() != null) {
                KeyStore keyStore = loadKeyStore();
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, config.getKeyStorePassword() != null
                        ? config.getKeyStorePassword().toCharArray() : new char[0]);
            }
            TrustManagerFactory tmf = null;
            if (config.getTrustStorePath() != null) {
                KeyStore trustStore = loadTrustStore();
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
            }
            SSLContext ctx = SSLContext.getInstance(config.getProtocols() != null
                    && !config.getProtocols().isEmpty()
                    ? config.getProtocols().getFirst() : "TLS");
            ctx.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null);
            return ctx;
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Failed to build SSLContext", e.getMessage(), e);
        }
    }

    private KeyStore loadKeyStore() {
        if (isPem(config.getKeyStoreType())) {
            return PemKeyStoreLoader.loadKeyStore(
                    config.getKeyStorePath(),
                    config.getKeyPath(),
                    config.getKeyStorePassword());
        }
        return KeyStoreLoader.load(
                config.getKeyStorePath(),
                config.getKeyStorePassword(),
                config.getKeyStoreType());
    }

    private KeyStore loadTrustStore() {
        if (isPem(config.getTrustStoreType())) {
            return PemKeyStoreLoader.loadTrustStore(config.getTrustStorePath());
        }
        return KeyStoreLoader.load(
                config.getTrustStorePath(),
                config.getTrustStorePassword(),
                config.getTrustStoreType());
    }

    private static boolean isPem(String type) {
        return PemKeyStoreLoader.TYPE.equalsIgnoreCase(type);
    }
}
