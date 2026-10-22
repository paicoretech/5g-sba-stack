package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MTlsValidatorTest {

    @Test
    void acceptsAllowlistedCn() {
        MTlsValidator validator = new MTlsValidator(Set.of("amf.example.com"));
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectX500Principal())
                .thenReturn(new X500Principal("CN=amf.example.com, O=Operator"));
        validator.validate(cert);
    }

    @Test
    void rejectsUnknownCn() {
        MTlsValidator validator = new MTlsValidator(Set.of("amf.example.com"));
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectX500Principal())
                .thenReturn(new X500Principal("CN=intruder.example.com"));
        assertThrows(AuthenticationException.class, () -> validator.validate(cert));
    }

    @Test
    void rejectsMissingCert() {
        MTlsValidator validator = new MTlsValidator(Set.of("amf.example.com"));
        assertThrows(AuthenticationException.class, () -> validator.validate(null));
    }

    @Test
    void extractCnHandlesEdgeCases() {
        assertEquals("a", MTlsValidator.extractCommonName(new X500Principal("CN=a, O=b")));
        assertNull(MTlsValidator.extractCommonName(new X500Principal("O=NoCnHere")));
        assertNull(MTlsValidator.extractCommonName(null));
    }

    @Test
    void rejectsCertWithoutCnInSubject() {
        MTlsValidator validator = new MTlsValidator(Set.of("amf.example.com"));
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectX500Principal())
                .thenReturn(new X500Principal("O=NoCnHere"));
        assertThrows(AuthenticationException.class, () -> validator.validate(cert));
    }

    @Test
    void validateSanAcceptsAllowedDns() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "amf.example.com")));
        MTlsValidator.validateSan(cert, Set.of("amf.example.com"), MTlsValidator.SanType.DNS);
    }

    @Test
    void validateSanAcceptsAllowedUri() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(6, "urn:smsf:nf-id:001")));
        MTlsValidator.validateSan(cert, Set.of("urn:smsf:nf-id:001"), MTlsValidator.SanType.URI);
    }

    @Test
    void validateSanRejectsTypeMismatch() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(6, "urn:smsf:nf-id:001")));
        Set<String> allowed = Set.of("urn:smsf:nf-id:001");
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, allowed, MTlsValidator.SanType.DNS));
    }

    @Test
    void validateSanRejectsUnknownValue() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "rogue.example.com")));
        Set<String> allowed = Set.of("amf.example.com");
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, allowed, MTlsValidator.SanType.DNS));
    }

    @Test
    void validateSanRejectsCertWithoutSanExtension() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames()).thenReturn(null);
        Set<String> allowed = Set.of("amf.example.com");
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, allowed, MTlsValidator.SanType.DNS));
    }

    @Test
    void validateSanSurfacesParsingFailure() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenThrow(new CertificateParsingException("bad san"));
        Set<String> allowed = Set.of("amf.example.com");
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, allowed, MTlsValidator.SanType.DNS));
    }

    @Test
    void validateSanRejectsMissingCert() {
        Set<String> allowed = Set.of("x");
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(null, allowed, MTlsValidator.SanType.DNS));
    }

    @Test
    void validateSanRejectsEmptyAllowList() throws Exception {
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        Mockito.when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "amf.example.com")));
        Set<String> empty = Set.of();
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, empty, MTlsValidator.SanType.DNS));
        assertThrows(AuthenticationException.class,
                () -> MTlsValidator.validateSan(cert, null, MTlsValidator.SanType.DNS));
    }
}
