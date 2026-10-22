package com.paicore.sba.stack.api.n21;

import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Resource paths of the {@code Nudm_UECM} SMSF registration API (3GPP TS 29.503, clause 6.2).
 *
 * <p>All three N21 operations act on the same resource, so the defaults below are shared
 * by registration (PUT), deregistration (DELETE) and query (GET). A deployment may still
 * override them — some UDMs expose a custom apiPrefix — which is why the templates are
 * configurable in {@link N21Configuration} rather than hard-coded at the call sites.</p>
 */
@Slf4j
public final class N21Paths {

    /** Placeholder used by TS 29.503 for the UE identifier in the resource URI. */
    public static final String UE_ID_PLACEHOLDER = "{ueId}";

    /** Alias accepted for convenience, since the UE identifier carried is the SUPI. */
    public static final String SUPI_PLACEHOLDER = "{supi}";

    /** {@code apiName} of the Nudm_UECM service (TS 29.503, clause 6.2.1). */
    public static final String API_NAME = "nudm-uecm";

    /** {@code apiVersion} assumed when the NRF advertises none. */
    public static final String DEFAULT_API_VERSION = "v1";

    /**
     * {@code apiSpecificResourceUriPart} of the SMSF 3GPP-access registration. Fixed by
     * TS 29.503: the NRF never returns this part, only the apiRoot and the API version.
     */
    // S1075: URI template per RFC 6570, not an OS path.
    @SuppressWarnings("java:S1075")
    public static final String SMSF_3GPP_ACCESS_RESOURCE = "/{ueId}/registrations/smsf-3gpp-access";

    /** Default SMSF 3GPP-access registration resource (PUT / DELETE / GET). */
    @SuppressWarnings("java:S1075")
    public static final String SMSF_3GPP_ACCESS_REGISTRATION =
            "/" + API_NAME + "/" + DEFAULT_API_VERSION + SMSF_3GPP_ACCESS_RESOURCE;

    private N21Paths() {
    }

    /**
     * Builds the {@code /{apiName}/{apiVersion}/{apiSpecificResourceUriPart}} portion of the
     * URI (TS 29.501, clause 4.4.1) from what the NRF advertised for the target service.
     *
     * @param apiName    service name from the NF profile, e.g. {@code nudm-uecm};
     *                   {@link #API_NAME} when blank
     * @param apiVersion {@code apiVersionInUri} from the NF profile, e.g. {@code v1};
     *                   {@link #DEFAULT_API_VERSION} when blank
     */
    public static String resourceTemplate(String apiName, String apiVersion) {
        String name = isPresent(apiName) ? apiName.trim() : API_NAME;
        String version = isPresent(apiVersion) ? apiVersion.trim() : DEFAULT_API_VERSION;
        return "/" + name + "/" + version + SMSF_3GPP_ACCESS_RESOURCE;
    }

    private static boolean isPresent(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }

    /**
     * Substitutes the UE identifier into a path template.
     *
     * <p>A template without a placeholder cannot address a subscriber resource, so the
     * encoded identifier is appended rather than issuing a request against the collection
     * URI — which the UDM would either reject or, worse, apply to the wrong resource. The
     * situation is a misconfiguration, hence the warning.</p>
     *
     * @param template path template, may contain {@link #UE_ID_PLACEHOLDER} or {@link #SUPI_PLACEHOLDER}
     * @param ueId     UE identifier (SUPI); {@code null} substitutes an empty string
     * @return the resolved, URL-encoded path
     */
    public static String resolve(String template, String ueId) {
        String encoded = Objects.isNull(ueId) ? "" : URLEncoder.encode(ueId, StandardCharsets.UTF_8);
        if (Objects.isNull(template) || template.isBlank()) {
            return "/" + encoded;
        }
        if (hasPlaceholder(template)) {
            return template.replace(UE_ID_PLACEHOLDER, encoded).replace(SUPI_PLACEHOLDER, encoded);
        }
        log.warn("N21 path template '{}' has no {} placeholder; appending the UE identifier. "
                + "Expected something like '{}'", template, UE_ID_PLACEHOLDER, SMSF_3GPP_ACCESS_REGISTRATION);
        return template.endsWith("/") ? template + encoded : template + "/" + encoded;
    }

    /** {@code true} when the template addresses a specific UE resource. */
    public static boolean hasPlaceholder(String template) {
        return Objects.nonNull(template)
                && (template.contains(UE_ID_PLACEHOLDER) || template.contains(SUPI_PLACEHOLDER));
    }
}
