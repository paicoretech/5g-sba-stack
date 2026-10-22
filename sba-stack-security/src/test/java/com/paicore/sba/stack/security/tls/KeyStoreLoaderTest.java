package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.exceptions.SdkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStoreLoaderTest {

    @TempDir
    Path tempDir;

    private Path emptyKeyStore;

    @BeforeEach
    void setup() throws Exception {
        emptyKeyStore = tempDir.resolve("empty.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, "changeit".toCharArray());
        try (OutputStream out = Files.newOutputStream(emptyKeyStore)) {
            ks.store(out, "changeit".toCharArray());
        }
    }

    @Test
    @DisplayName("load returns a usable KeyStore when given a valid PKCS12 file and password")
    void loadShouldReturnInitializedKeyStore() {
        KeyStore loaded = KeyStoreLoader.load(emptyKeyStore.toString(), "changeit", "PKCS12");
        assertNotNull(loaded);
        assertEquals("PKCS12", loaded.getType());
    }

    @Test
    @DisplayName("load accepts a null password and converts it to a null char[]")
    void loadShouldAcceptNullPassword() throws Exception {
        Path noPwd = tempDir.resolve("nopwd.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        try (OutputStream out = Files.newOutputStream(noPwd)) {
            ks.store(out, new char[0]);
        }
        // A PKCS12 without integrity password parses fine with null password.
        KeyStore loaded = KeyStoreLoader.load(noPwd.toString(), null, "PKCS12");
        assertNotNull(loaded);
    }

    @Test
    @DisplayName("load throws SdkException when path is null")
    void loadShouldRejectNullPath() {
        SdkException ex = assertThrows(SdkException.class,
                () -> KeyStoreLoader.load(null, "x", "PKCS12"));
        assertTrue(ex.getDetails().contains("null"));
    }

    @Test
    @DisplayName("load throws SdkException when path is blank")
    void loadShouldRejectBlankPath() {
        assertThrows(SdkException.class,
                () -> KeyStoreLoader.load("   ", "x", "PKCS12"));
    }

    @Test
    @DisplayName("load wraps IOException when the file does not exist")
    void loadShouldWrapIoExceptionForMissingFile() {
        SdkException ex = assertThrows(SdkException.class,
                () -> KeyStoreLoader.load("/non/existent/keystore.p12", "x", "PKCS12"));
        assertTrue(ex.getMessage().contains("Failed to load keystore"));
    }

    @Test
    @DisplayName("load wraps KeyStoreException for unsupported types")
    void loadShouldWrapKeyStoreExceptionForUnsupportedType() {
        String path = emptyKeyStore.toString();
        assertThrows(SdkException.class,
                () -> KeyStoreLoader.load(path, "changeit", "NOSUCHTYPE"));
    }

    @Test
    @DisplayName("load wraps the JDK IOException when the password is wrong")
    void loadShouldWrapWrongPasswordError() {
        String path = emptyKeyStore.toString();
        assertThrows(SdkException.class,
                () -> KeyStoreLoader.load(path, "wrong-password", "PKCS12"));
    }

    @Test
    @DisplayName("private constructor refuses instantiation (utility class)")
    void privateConstructorThrows() throws Exception {
        java.lang.reflect.Constructor<KeyStoreLoader> ctor =
                KeyStoreLoader.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        try {
            ctor.newInstance();
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
            return;
        }
        throw new AssertionError("Expected InvocationTargetException");
    }
}
