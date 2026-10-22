package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.utils.ErrorCodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

public final class KeyStoreLoader {

    private KeyStoreLoader() {
        throw new IllegalStateException("Utility class");
    }

    public static KeyStore load(String path, String password, String type) {
        if (path == null || path.isBlank()) {
            throw new SdkException(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "KeyStore path is required", "path is null/blank");
        }
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(in, password != null ? password.toCharArray() : null);
            return keyStore;
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Failed to load keystore", path, e);
        }
    }
}
