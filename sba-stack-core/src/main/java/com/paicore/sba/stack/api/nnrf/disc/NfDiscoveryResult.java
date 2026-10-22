package com.paicore.sba.stack.api.nnrf.disc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of an {@code Nnrf_NFDiscovery} search: the candidate instances already ranked
 * by preference, plus the instant at which the NRF's {@code validityPeriod} expires.
 *
 * @param instances candidates, best first (see {@link NfInstanceSelector})
 * @param expiresAt when this result must be re-discovered; {@code null} means no expiry
 */
public record NfDiscoveryResult(List<DiscoveredNfInstance> instances, Instant expiresAt) {

    public NfDiscoveryResult {
        instances = Objects.isNull(instances) ? List.of() : List.copyOf(instances);
    }

    /** An empty, immediately stale result. */
    public static NfDiscoveryResult empty() {
        return new NfDiscoveryResult(List.of(), Instant.EPOCH);
    }

    public boolean isEmpty() {
        return instances.isEmpty();
    }

    /** The preferred candidate, if any. */
    public Optional<DiscoveredNfInstance> preferred() {
        return instances.isEmpty() ? Optional.empty() : Optional.of(instances.get(0));
    }

    /** apiRoot of the preferred candidate, if any. */
    public Optional<String> preferredApiRoot() {
        return preferred().map(DiscoveredNfInstance::apiRoot);
    }

    /** {@code true} when the NRF-supplied validity period has elapsed at {@code now}. */
    public boolean isExpired(Instant now) {
        return Objects.nonNull(expiresAt) && !now.isBefore(expiresAt);
    }
}
