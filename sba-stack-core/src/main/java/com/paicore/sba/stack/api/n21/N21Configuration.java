package com.paicore.sba.stack.api.n21;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Transport/security configuration for the N21 client (SMSF &rarr; UDM).
 *
 * <p>Mirrors the semantics of the SMSF Module's {@code Http2GatewayUDMSecurity}
 * gateway configuration, but is defined here so the stack stays independent of
 * SMSF-specific types. A consuming module maps its own gateway configuration
 * into this object.</p>
 *
 * <p>URL resolution has two modes:</p>
 * <ul>
 *   <li>{@code nrfDiscoveryEnabled == false}: the UDM {@code apiRoot} is taken
 *       from {@link #apiRoot} and combined with the configured paths.</li>
 *   <li>{@code nrfDiscoveryEnabled == true}: the UDM {@code apiRoot} is resolved
 *       at connect time via NRF discovery (target NF type {@code UDM}) and then
 *       combined with the configured paths.</li>
 * </ul>
 *
 * <p>The three path templates are optional. When left unset the client composes
 * {@code /{apiName}/{apiVersion}/...} from what the NRF advertised for the target service
 * (TS 29.501 clause 4.4.1), falling back to {@link N21Paths#SMSF_3GPP_ACCESS_REGISTRATION}.
 * Setting one overrides that resolution for the corresponding operation.</p>
 */
@Getter
@Builder
@ToString(exclude = {"clientPrivateKeyPath"})
public class N21Configuration {

    // --- Endpoint resolution -------------------------------------------------

    /** When {@code true}, resolve the UDM apiRoot through NRF discovery. */
    private final boolean nrfDiscoveryEnabled;

    /** UDM apiRoot (e.g. {@code https://udm.example.com:8443}). Used only when discovery is disabled. */
    private final String apiRoot;

    /**
     * Path template for SMSF registration (PUT), appended to the resolved apiRoot. Optional:
     * when unset, the client derives it from the service the NRF advertised.
     */
    private final String registrationPath;

    /** Path template for SMSF deregistration (DELETE). Optional, same resolution as above. */
    private final String deregistrationPath;

    /** Path template for subscriber/registration query (GET). Optional, same resolution as above. */
    private final String subscriberQueryPath;

    // --- NRF discovery -------------------------------------------------------

    /** NRF discovery resource. */
    // S1075: URL path per RFC 3986, not an OS path.
    @SuppressWarnings("java:S1075")
    @Builder.Default
    private final String discoveryPath = "/nnrf-disc/v1/nf-instances";

    /** Target NF type requested from the NRF. */
    @Builder.Default
    private final String targetNfType = "UDM";

    /** Service the target UDM must expose. */
    @Builder.Default
    private final String targetServiceName = "nudm-uecm";

    /** Requester NF type reported to the NRF. */
    @Builder.Default
    private final String requesterNfType = "SMSF";

    /** Requester NF instance id reported to the NRF, so it can scope/authorize the search. */
    private final String requesterNfInstanceId;

    /**
     * When {@code true}, subscriber-scoped selectors ({@code supi}, {@code routing-indicator},
     * {@code group-id-list}) are added to the discovery query so the NRF returns the UDM that
     * actually serves the UE. Disable it for deployments with a single UDM group.
     */
    @Builder.Default
    private final boolean subscriberAwareDiscovery = true;

    /** Expiry applied to a discovery result when the NRF returns no {@code validityPeriod}. */
    @Builder.Default
    private final int discoveryCacheTtlSeconds = 300;

    /**
     * Maximum number of distinct UDM endpoints kept connected at once. Subscriber-aware
     * discovery can steer different UEs to different UDM instances; this bounds how many
     * transports that may open.
     */
    @Builder.Default
    private final int maxEndpoints = 4;

    // --- mTLS ----------------------------------------------------------------

    private final boolean mtlsEnabled;
    private final String clientCertificatePath;
    private final String clientPrivateKeyPath;
    private final String truststoreCaPath;

    /** Whether to verify the server hostname against its certificate. Defaults to {@link #mtlsEnabled}. */
    @Builder.Default
    private final Boolean verifyHostname = null;

    // --- OAuth2 --------------------------------------------------------------

    /** When {@code true}, an Authorization header is attached to every request. */
    private final boolean oauth2Enabled;

    /** Name of the authorization header. Defaults to {@code Authorization}. */
    @Builder.Default
    private final String authorizationHeaderName = "Authorization";

    // --- Timeouts (milliseconds; 0 = provider default) -----------------------

    private final int connectionTimeoutMs;
    private final int requestTimeoutMs;
    private final int idleTimeoutMs;

    /** Effective hostname verification flag ({@link #verifyHostname} or, if unset, {@link #mtlsEnabled}). */
    public boolean isVerifyHostnameEffective() {
        return verifyHostname != null ? verifyHostname : mtlsEnabled;
    }
}
