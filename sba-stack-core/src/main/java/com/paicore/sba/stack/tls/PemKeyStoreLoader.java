package com.paicore.sba.stack.tls;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.utils.ErrorCodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Builds in-memory PKCS12 keystores from PEM material (client cert + key, or
 * CA bundle). Lets operators point {@code TlsConfig} at {@code .crt}/{@code .key}
 * files produced by cert-manager / Kubernetes without converting to PKCS12 by
 * hand.
 *
 * <p>Selected via {@code keyStoreType}/{@code trustStoreType} == {@link #TYPE}.
 *
 * <p>Supported private key encoding: <b>PKCS#8</b> (unencrypted). PKCS#1 RSA
 * ({@code BEGIN RSA PRIVATE KEY}) and SEC1 EC ({@code BEGIN EC PRIVATE KEY})
 * formats raise an {@link SdkException} with the {@code openssl pkcs8 -topk8}
 * one-liner the operator needs to convert.
 */
public final class PemKeyStoreLoader {

    public static final String TYPE = "PEM";

    private static final String CLIENT_ALIAS = "client";
    private static final String CA_ALIAS_PREFIX = "ca-";
    private static final String[] KEY_ALGORITHMS = {"RSA", "EC", "DSA", "Ed25519"};
    private static final String PKCS8_TAG = "PRIVATE KEY";
    private static final String ENCRYPTED_PKCS8_TAG = "ENCRYPTED PRIVATE KEY";
    private static final String PKCS1_RSA_TAG = "RSA PRIVATE KEY";
    private static final String SEC1_EC_TAG = "EC PRIVATE KEY";
    private static final String PEM_BEGIN_PREFIX = "BEGIN ";
    private static final String PEM_BOUNDARY = "-----";
    private static final byte[] EMPTY_BYTES = new byte[0];

    private PemKeyStoreLoader() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Build a PKCS12 keystore in memory from a PEM certificate file (leaf +
     * optional chain) and a separate PEM PKCS#8 private key file.
     */
    public static KeyStore loadKeyStore(String certPath, String keyPath, String keyPassword) {
        requirePath(certPath, "cert path");
        requirePath(keyPath, "key path");
        try {
            List<X509Certificate> chain = readCertificateChain(certPath);
            if (chain.isEmpty()) {
                throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                        "No certificate found in PEM file", certPath);
            }
            PrivateKey key = readPrivateKey(keyPath);
            char[] password = keyPassword != null ? keyPassword.toCharArray() : new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, password);
            ks.setKeyEntry(CLIENT_ALIAS, key, password, chain.toArray(new Certificate[0]));
            return ks;
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Failed to build PEM keystore", certPath + " / " + keyPath, e);
        }
    }

    /**
     * Build a PKCS12 truststore in memory from PEM certificate material.
     *
     * <p>The {@code caPath} may be either:
     * <ul>
     *   <li>a <b>file</b> — one or more concatenated PEM CA certificates, or</li>
     *   <li>a <b>directory</b> — every {@code .crt}/{@code .cert}/{@code .pem}
     *       file in it is loaded (sorted by name for deterministic aliases),
     *       so operators can drop multiple per-server CAs side by side.</li>
     * </ul>
     */
    public static KeyStore loadTrustStore(String caPath) {
        requirePath(caPath, "ca path");
        try {
            List<X509Certificate> cas = readTrustMaterial(caPath);
            if (cas.isEmpty()) {
                throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                        "No certificate found in PEM source", caPath);
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, new char[0]);
            for (int i = 0; i < cas.size(); i++) {
                ks.setCertificateEntry(CA_ALIAS_PREFIX + i, cas.get(i));
            }
            return ks;
        } catch (SdkException e) {
            throw e;
        } catch (Exception e) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Failed to build PEM truststore", caPath, e);
        }
    }

    private static List<X509Certificate> readTrustMaterial(String caPath) throws IOException {
        Path root = Path.of(caPath);
        if (!Files.isDirectory(root)) {
            return readCertificateChain(caPath);
        }
        List<X509Certificate> all = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> pemFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(PemKeyStoreLoader::hasPemExtension)
                    .sorted()
                    .toList();
            for (Path file : pemFiles) {
                all.addAll(readCertificateChain(file.toString()));
            }
        }
        return all;
    }

    private static boolean hasPemExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".crt") || name.endsWith(".cert") || name.endsWith(".pem");
    }

    private static List<X509Certificate> readCertificateChain(String path) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            List<X509Certificate> result = new ArrayList<>(certs.size());
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) {
                    result.add(x);
                }
            }
            return result;
        } catch (java.security.cert.CertificateException e) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Invalid PEM certificate", path, e);
        }
    }

    private static PrivateKey readPrivateKey(String path) throws IOException {
        String content = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
        if (content.contains(PEM_BEGIN_PREFIX + PKCS1_RSA_TAG) || content.contains(PEM_BEGIN_PREFIX + SEC1_EC_TAG)) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "PKCS#1 / SEC1 private keys are not supported",
                    "Convert with: openssl pkcs8 -topk8 -nocrypt -in " + path + " -out " + path + ".pkcs8");
        }
        if (content.contains(PEM_BEGIN_PREFIX + ENCRYPTED_PKCS8_TAG)) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "Encrypted PKCS#8 keys are not supported",
                    "Decrypt with: openssl pkcs8 -in " + path + " -out " + path + ".unencrypted -nocrypt");
        }
        byte[] der = decodePemBlock(content, PKCS8_TAG);
        if (der.length == 0) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "PEM file does not contain a PKCS#8 private key block", path);
        }
        return generatePrivateKey(der);
    }

    private static PrivateKey generatePrivateKey(byte[] der) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) {
                // Try the next algorithm — the PKCS#8 OID disambiguates, but Java's
                // KeyFactory API can't tell us the algorithm without trying.
            }
        }
        throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                "Unsupported private key algorithm",
                "Tried: " + String.join(", ", KEY_ALGORITHMS));
    }

    private static byte[] decodePemBlock(String content, String tag) {
        String begin = PEM_BOUNDARY + PEM_BEGIN_PREFIX + tag + PEM_BOUNDARY;
        String end = PEM_BOUNDARY + "END " + tag + PEM_BOUNDARY;
        int s = content.indexOf(begin);
        if (s < 0) {
            return EMPTY_BYTES;
        }
        int e = content.indexOf(end, s);
        if (e < 0) {
            return EMPTY_BYTES;
        }
        String body = content.substring(s + begin.length(), e).replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }

    private static void requirePath(String path, String label) {
        if (path == null || path.isBlank()) {
            throw new SdkException(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "PEM " + label + " is required", "path is null/blank");
        }
    }
}
