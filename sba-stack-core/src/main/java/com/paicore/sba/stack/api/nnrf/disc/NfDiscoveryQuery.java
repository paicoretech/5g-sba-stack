package com.paicore.sba.stack.api.nnrf.disc;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Query parameters for an {@code Nnrf_NFDiscovery} request (3GPP TS 29.510, clause 6.2.3.2.3.1).
 *
 * <p>Pure value object: it only renders the query string, it performs no I/O and knows
 * nothing about the transport. Any NF acting as a discovery requester can use it, which
 * is why it lives in the core and not next to a particular service client.</p>
 *
 * <p>Subscriber-scoped selectors ({@code supi}, {@code routing-indicator},
 * {@code group-id-list}) are what let an NRF steer the requester to the UDM/UDR/AUSF
 * instance that actually serves that subscriber; they are optional and simply omitted
 * when not set.</p>
 */
@Getter
@Builder
public class NfDiscoveryQuery {

    /** Target NF type, e.g. {@code UDM}. Required. */
    private final String targetNfType;

    /** Requester NF type, e.g. {@code SMSF}. */
    private final String requesterNfType;

    /** Requester NF instance id (UUID string), so the NRF can authorize/scope the search. */
    private final String requesterNfInstanceId;

    /** Service names the target must expose, e.g. {@code nudm-uecm}. */
    @Singular("serviceName")
    private final List<String> serviceNames;

    /** Subscriber identifier the target must serve, e.g. {@code imsi-001010000000001}. */
    private final String supi;

    /** Generic public subscription identifier, e.g. {@code msisdn-34600000000}. */
    private final String gpsi;

    /** Routing indicator (from the UE's SUCI/registration) used for UDM group selection. */
    private final String routingIndicator;

    /** NF group ids of the target NF (e.g. the UE's {@code udmGroupId}). */
    @Singular("groupId")
    private final List<String> groupIdList;

    /** Restricts the search to a single, already known target instance. */
    private final String targetNfInstanceId;

    /** Preferred locality of the target instance, matched against the NF profile. */
    private final String preferredLocality;

    /** Maximum number of instances the NRF should return. {@code 0} omits the parameter. */
    private final int limit;

    /**
     * Renders the parameters as a URL-encoded query string, without a leading {@code ?}.
     * Parameter order is deterministic so the result can be used as a cache key.
     */
    public String toQueryString() {
        StringJoiner joiner = new StringJoiner("&");
        append(joiner, "target-nf-type", targetNfType);
        append(joiner, "requester-nf-type", requesterNfType);
        append(joiner, "requester-nf-instance-id", requesterNfInstanceId);
        append(joiner, "service-names", csv(serviceNames));
        append(joiner, "supi", supi);
        append(joiner, "gpsi", gpsi);
        append(joiner, "routing-indicator", routingIndicator);
        append(joiner, "group-id-list", csv(groupIdList));
        append(joiner, "target-nf-instance-id", targetNfInstanceId);
        append(joiner, "preferred-locality", preferredLocality);
        if (limit > 0) {
            append(joiner, "limit", Integer.toString(limit));
        }
        return joiner.toString();
    }

    /** Stable cache key for this query. */
    public String cacheKey() {
        return toQueryString();
    }

    /**
     * @return the first requested service name, or {@code null} when none was set. Used to
     *         pick the matching entry in the target's {@code nfServices} list.
     */
    public String getPrimaryServiceName() {
        if (Objects.isNull(serviceNames) || serviceNames.isEmpty()) {
            return null;
        }
        String first = serviceNames.get(0);
        return Objects.nonNull(first) && !first.isBlank() ? first.trim() : null;
    }

    private static void append(StringJoiner joiner, String name, String value) {
        if (Objects.nonNull(value) && !value.isBlank()) {
            joiner.add(name + "=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
        }
    }

    private static String csv(List<String> values) {
        if (Objects.isNull(values) || values.isEmpty()) {
            return null;
        }
        List<String> present = new ArrayList<>(values.size());
        for (String value : values) {
            if (Objects.nonNull(value) && !value.isBlank()) {
                present.add(value.trim());
            }
        }
        return present.isEmpty() ? null : String.join(",", present);
    }
}
