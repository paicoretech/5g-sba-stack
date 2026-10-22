package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.tls.PemKeyStoreLoader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.web.server.ServerWebExchange;

import java.security.KeyStore;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class PeerCertValidator {

    private static final int SAN_TYPE_URI = 6;
    private static final String URN_UUID_PREFIX = "urn:uuid:";

    public Optional<X509Certificate> extract(ServerWebExchange exchange) {
        SslInfo ssl = exchange.getRequest().getSslInfo();
        if (Objects.isNull(ssl)) {
            return Optional.empty();
        }
        X509Certificate[] chain = ssl.getPeerCertificates();
        if (Objects.isNull(chain) || chain.length == 0) {
            return Optional.empty();
        }
        return Optional.of(chain[0]);
    }

    public boolean matchesPinned(X509Certificate peer, String expectedCertPath) {
        if (Objects.isNull(expectedCertPath) || expectedCertPath.isBlank()) {
            return true;
        }
        try {
            KeyStore ks = PemKeyStoreLoader.loadTrustStore(expectedCertPath);
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                X509Certificate expected = (X509Certificate) ks.getCertificate(aliases.nextElement());
                if (Objects.nonNull(expected)
                        && Arrays.equals(peer.getEncoded(), expected.getEncoded())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.error("Failed to read expected peer cert {}: {}", expectedCertPath, ex.getMessage());
            return false;
        }
    }

    public boolean sanContainsPrincipalId(X509Certificate peer, String principalId) {
        if (Objects.isNull(principalId) || principalId.isBlank()) {
            return false;
        }
        String idLower = principalId.toLowerCase(Locale.ROOT);
        String urnFormLower = (URN_UUID_PREFIX + principalId).toLowerCase(Locale.ROOT);
        try {
            Collection<List<?>> sans = peer.getSubjectAlternativeNames();
            if (Objects.isNull(sans)) {
                return false;
            }
            return sans.stream().anyMatch(san -> sanMatches(san, idLower, urnFormLower));
        } catch (CertificateParsingException ex) {
            log.warn("Cannot parse SAN of peer cert: {}", ex.getMessage());
            return false;
        }
    }

    private static boolean sanMatches(List<?> san, String idLower, String urnFormLower) {
        if (san.size() < 2 || !(san.get(0) instanceof Integer type) || type != SAN_TYPE_URI) {
            return false;
        }
        if (!(san.get(1) instanceof String raw)) {
            return false;
        }
        String candidate = raw.toLowerCase(Locale.ROOT);
        return candidate.equals(idLower) || candidate.equals(urnFormLower);
    }
}
