package com.paicore.sba.stack.security.tls;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import lombok.RequiredArgsConstructor;

import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a peer X.509 certificate after the TLS handshake. CN allowlist via
 * {@link #validate(X509Certificate)}; SAN (DNS/URI) checks per 3GPP TS 33.310
 * §6.1.3c via {@link #validateSan(X509Certificate, Collection, SanType)}.
 */
@RequiredArgsConstructor
public class MTlsValidator {

    private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+)");

    private final Set<String> allowedCommonNames;

    public void validate(X509Certificate peerCert) {
        if (peerCert == null) {
            throw new AuthenticationException("Peer certificate is missing");
        }
        String cn = extractCommonName(peerCert.getSubjectX500Principal());
        if (cn == null || !allowedCommonNames.contains(cn)) {
            throw new AuthenticationException("Peer CN not allowed: " + cn);
        }
    }

    public static void validateSan(X509Certificate peerCert, Collection<String> allowedSans, SanType type) {
        if (peerCert == null) {
            throw new AuthenticationException("Peer certificate is missing");
        }
        if (allowedSans == null || allowedSans.isEmpty()) {
            throw new AuthenticationException("No allowed SAN values configured");
        }
        Collection<List<?>> sanEntries;
        try {
            sanEntries = peerCert.getSubjectAlternativeNames();
        } catch (CertificateParsingException ex) {
            throw new AuthenticationException("Failed to parse SAN extension", ex);
        }
        if (sanEntries == null || sanEntries.isEmpty()) {
            throw new AuthenticationException("Peer certificate has no SAN extension");
        }
        int target = type.tag();
        boolean matched = sanEntries.stream().anyMatch(entry -> matchesSan(entry, target, allowedSans));
        if (!matched) {
            throw new AuthenticationException(
                    "Peer certificate does not present an allowed " + type + " SAN");
        }
    }

    private static boolean matchesSan(List<?> entry, int target, Collection<String> allowedSans) {
        if (entry == null || entry.size() < 2) {
            return false;
        }
        Object rawTag = entry.get(0);
        Object rawValue = entry.get(1);
        if (!(rawTag instanceof Integer sanTag) || !(rawValue instanceof String sanValue)) {
            return false;
        }
        return sanTag == target && allowedSans.contains(sanValue);
    }

    static String extractCommonName(X500Principal principal) {
        if (principal == null) {
            return null;
        }
        var matcher = CN_PATTERN.matcher(principal.getName());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** RFC 5280 §4.2.1.6 GeneralName tags. */
    public enum SanType {
        DNS(2),
        URI(6);

        private final int tag;

        SanType(int tag) {
            this.tag = tag;
        }

        int tag() {
            return tag;
        }
    }
}
