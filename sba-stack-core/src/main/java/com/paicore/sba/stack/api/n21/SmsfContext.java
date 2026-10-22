package com.paicore.sba.stack.api.n21;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Local SMSF identity supplied by the SMSF Module (or any consuming core).
 *
 * <p>This is the static SMSF-side identity required to populate the N21
 * {@code SmsfRegistration} payload. It is provided once when the
 * {@link N21Client} is created and reused for every request; the per-request
 * subscriber data (SUPI) and the serving {@code plmnId} come from the
 * {@code UeSmsContextData} received from the AMF instead.</p>
 *
 * <p>{@code smsfMapAddress} and {@code smsfDiameterAddress} are optional and
 * must only be set when the corresponding interworking feature (MAP / Diameter)
 * is enabled.</p>
 */
@Getter
@Builder
@ToString
public class SmsfContext {

    /** SMSF NF instance id (UUID string), maps to {@code smsfInstanceId}. */
    private final String smsfInstanceId;

    /** NF set id of the SMSF, maps to {@code smsfSetId}. Optional. */
    private final String smsfSetId;

    /** Supported features bitmap (hex string), maps to {@code supportedFeatures}. Optional. */
    private final String supportedFeatures;

    /**
     * International E.164 number, maps to {@code smsfMAPAddress}. Optional. A leading {@code +}
     * and the usual separators are stripped; a value that is still not 1-15 digits afterwards is
     * dropped with a warning rather than sent malformed.
     */
    private final String smsfMapAddress;

    /**
     * Diameter address, maps to {@code smsfDiameterAddress}. Optional. Accepts either
     * {@code name@realm} or a bare identity (used as both name and realm); both parts must be
     * valid FQDNs (TS 29.571) or the address is dropped with a warning.
     */
    private final String smsfDiameterAddress;

    /**
     * Home PLMN MCC used as {@code plmnId} when the AMF supplies no GUAMI. Optional:
     * the serving PLMN reported by the AMF always wins when present.
     */
    private final String homePlmnMcc;

    /** Home PLMN MNC, paired with {@link #homePlmnMcc}. Optional. */
    private final String homePlmnMnc;

    /**
     * Indicates to the UDM that this SMSF supports SBI-based MT delivery
     * ({@code smsfSbiSupInd}). Optional; left out of the payload when {@code null}.
     */
    private final Boolean smsfSbiSupInd;
}
