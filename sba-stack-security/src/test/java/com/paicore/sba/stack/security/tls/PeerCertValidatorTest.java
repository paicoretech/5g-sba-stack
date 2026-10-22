package com.paicore.sba.stack.security.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.web.server.ServerWebExchange;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeerCertValidatorTest {

    private static final String AMF_PRINCIPAL_ID = "00000000-0000-0000-0000-000000000amf";
    private static final String AMF_SAN_URI = "urn:uuid:" + AMF_PRINCIPAL_ID;
    private static final String AMF_CN = "amf.test.paic";
    private static final String ROGUE_CN = "rogue.test.paic";

    @TempDir
    Path tempDir;

    private PeerCertValidator validator;
    private X509Certificate amfCert;
    private X509Certificate rogueCert;
    private Path amfCertPath;
    private Path rogueCertPath;

    @BeforeEach
    void setUp() throws Exception {
        validator = new PeerCertValidator();
        amfCert = generateCert(AMF_CN, AMF_SAN_URI);
        rogueCert = generateCert(ROGUE_CN, "urn:uuid:rogue-00000000-0000-0000-0000-000000rogue");
        amfCertPath = writePem(amfCert, tempDir.resolve("amf.cert.pem"));
        rogueCertPath = writePem(rogueCert, tempDir.resolve("rogue.cert.pem"));
    }

    @Test
    @DisplayName("extract: returns peer cert when SslInfo carries client chain")
    void extractsPeerCert() {
        Optional<X509Certificate> result = validator.extract(exchangeWithPeer(amfCert));

        assertThat(result)
                .isPresent()
                .hasValueSatisfying(c -> assertThat(c.getSubjectX500Principal().getName())
                        .contains("CN=" + AMF_CN));
    }

    @Test
    @DisplayName("extract: empty when no SslInfo (h2c/plain request)")
    void extractsEmptyWhenNoSsl() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(exchange.getRequest()).thenReturn(req);
        when(req.getSslInfo()).thenReturn(null);

        assertThat(validator.extract(exchange)).isEmpty();
    }

    @Test
    @DisplayName("extract: empty when SslInfo carries no client chain (clientAuth=OPTIONAL, no cert presented)")
    void extractsEmptyWhenNoChain() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        SslInfo ssl = mock(SslInfo.class);
        when(exchange.getRequest()).thenReturn(req);
        when(req.getSslInfo()).thenReturn(ssl);
        when(ssl.getPeerCertificates()).thenReturn(null);

        assertThat(validator.extract(exchange)).isEmpty();
    }

    @Test
    @DisplayName("extract: empty when SslInfo carries empty chain")
    void extractsEmptyWhenEmptyChain() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        SslInfo ssl = mock(SslInfo.class);
        when(exchange.getRequest()).thenReturn(req);
        when(req.getSslInfo()).thenReturn(ssl);
        when(ssl.getPeerCertificates()).thenReturn(new X509Certificate[0]);

        assertThat(validator.extract(exchange)).isEmpty();
    }

    @Test
    @DisplayName("matchesPinned: true when expected path is blank (no pinning configured)")
    void noPinningPath() {
        assertThat(validator.matchesPinned(amfCert, "")).isTrue();
        assertThat(validator.matchesPinned(amfCert, null)).isTrue();
        assertThat(validator.matchesPinned(amfCert, "   ")).isTrue();
    }

    @Test
    @DisplayName("matchesPinned: true when peer matches expected cert byte-for-byte")
    void pinningMatches() {
        assertThat(validator.matchesPinned(amfCert, amfCertPath.toString())).isTrue();
    }

    @Test
    @DisplayName("matchesPinned: false when peer differs from expected cert")
    void pinningMismatch() {
        assertThat(validator.matchesPinned(amfCert, rogueCertPath.toString())).isFalse();
    }

    @Test
    @DisplayName("matchesPinned: false when expected file is unreadable")
    void pinningUnreadable() {
        assertThat(validator.matchesPinned(amfCert, "/does/not/exist.pem")).isFalse();
    }

    @Test
    @DisplayName("sanContainsPrincipalId: matches when principal id appears as urn:uuid SAN URI")
    void sanMatchesUrnUuid() {
        assertThat(validator.sanContainsPrincipalId(amfCert, AMF_PRINCIPAL_ID)).isTrue();
    }

    @Test
    @DisplayName("sanContainsPrincipalId: matches when principal id is the full URI")
    void sanMatchesFullUri() {
        assertThat(validator.sanContainsPrincipalId(amfCert, AMF_SAN_URI)).isTrue();
    }

    @Test
    @DisplayName("sanContainsPrincipalId: false when principal id does not appear in SAN")
    void sanMismatch() {
        assertThat(validator.sanContainsPrincipalId(amfCert, "unknown-nf-id")).isFalse();
    }

    @Test
    @DisplayName("sanContainsPrincipalId: false when principal id is blank/null")
    void sanRejectsBlank() {
        assertThat(validator.sanContainsPrincipalId(amfCert, "")).isFalse();
        assertThat(validator.sanContainsPrincipalId(amfCert, null)).isFalse();
        assertThat(validator.sanContainsPrincipalId(amfCert, "   ")).isFalse();
    }

    @Test
    @DisplayName("sanContainsPrincipalId: false when cert has no SAN extension")
    void sanRejectsCertWithoutSanExtension() throws Exception {
        X509Certificate certWithoutSan = generateCertWithoutSan("no-san.test.paic");

        assertThat(validator.sanContainsPrincipalId(certWithoutSan, AMF_PRINCIPAL_ID)).isFalse();
    }

    private static X509Certificate generateCert(String commonName, String sanUri) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X500Name subject = new X500Name("CN=" + commonName);
        Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 86_400_000L);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter, subject, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, sanUri)));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static X509Certificate generateCertWithoutSan(String commonName) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X500Name subject = new X500Name("CN=" + commonName);
        Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 86_400_000L);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter, subject, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static Path writePem(X509Certificate cert, Path target) throws Exception {
        Base64.Encoder mime = Base64.getMimeEncoder(64, "\n".getBytes());
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write("-----BEGIN CERTIFICATE-----\n".getBytes());
            out.write(mime.encode(cert.getEncoded()));
            out.write("\n-----END CERTIFICATE-----\n".getBytes());
        }
        return target;
    }

    private static ServerWebExchange exchangeWithPeer(X509Certificate cert) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        SslInfo ssl = mock(SslInfo.class);
        when(exchange.getRequest()).thenReturn(req);
        when(req.getSslInfo()).thenReturn(ssl);
        when(ssl.getPeerCertificates()).thenReturn(new X509Certificate[]{cert});
        return exchange;
    }
}
