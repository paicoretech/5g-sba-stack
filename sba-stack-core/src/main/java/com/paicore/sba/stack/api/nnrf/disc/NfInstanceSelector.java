package com.paicore.sba.stack.api.nnrf.disc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Parses an {@code Nnrf_NFDiscovery} {@code SearchResult} (3GPP TS 29.510) and ranks the
 * returned NF instances.
 *
 * <p>Pure and transport-agnostic: it takes the response body as a string so it can be
 * reused by any requester NF regardless of how the NRF was reached.</p>
 *
 * <p>Ranking follows TS 29.510 clause 6.1.6.2.2: only {@code REGISTERED} instances are
 * eligible, then lowest {@code priority}, then highest {@code capacity}, then lowest
 * {@code load}. Service-level {@code priority}/{@code capacity}/{@code load} override the
 * instance-level values when present, as the spec allows per-service overrides.</p>
 */
@Slf4j
public final class NfInstanceSelector {

    private static final String STATUS_REGISTERED = "REGISTERED";
    private static final String DEFAULT_SCHEME = "https";
    private static final String NF_INSTANCE_ID = "nfInstanceId";

    /** Sentinel meaning "this instance does not expose the requested service". */
    private static final Map<?, ?> NO_MATCH = Map.of();

    private static final Comparator<DiscoveredNfInstance> BY_PREFERENCE =
            Comparator.comparingInt(DiscoveredNfInstance::priority)
                    .thenComparing(Comparator.comparingInt(DiscoveredNfInstance::capacity).reversed())
                    .thenComparingInt(DiscoveredNfInstance::load);

    private NfInstanceSelector() {
    }

    /**
     * Parses and ranks a {@code SearchResult} body.
     *
     * @param body        raw JSON returned by the NRF
     * @param serviceName service the target must expose (e.g. {@code nudm-uecm}); when
     *                    {@code null} the first service of each instance is used
     * @param now         reference instant used to turn {@code validityPeriod} into an expiry
     * @param defaultTtl  expiry applied when the NRF omits {@code validityPeriod};
     *                    {@code null} means the result never expires
     * @return the ranked result, never {@code null}
     */
    public static NfDiscoveryResult parse(String body, String serviceName, Instant now, Duration defaultTtl) {
        if (Objects.isNull(body) || body.isBlank()) {
            return NfDiscoveryResult.empty();
        }
        Map<String, Object> root;
        try {
            root = SdkConverter.stringToObject(body, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Cannot parse NRF SearchResult: {}", ex.getMessage());
            return NfDiscoveryResult.empty();
        }
        if (Objects.isNull(root)) {
            return NfDiscoveryResult.empty();
        }

        List<DiscoveredNfInstance> candidates = new ArrayList<>();
        if (root.get("nfInstances") instanceof List<?> instances) {
            for (Object instance : instances) {
                if (instance instanceof Map<?, ?> map) {
                    toCandidate(map, serviceName).ifPresent(candidates::add);
                }
            }
        }
        candidates.sort(BY_PREFERENCE);
        return new NfDiscoveryResult(candidates, expiryOf(root, now, defaultTtl));
    }

    private static java.util.Optional<DiscoveredNfInstance> toCandidate(Map<?, ?> instance, String serviceName) {
        String status = stringOrNull(instance.get("nfStatus"));
        if (Objects.nonNull(status) && !STATUS_REGISTERED.equalsIgnoreCase(status)) {
            log.debug("Skipping NF instance {} with nfStatus={}", instance.get(NF_INSTANCE_ID), status);
            return java.util.Optional.empty();
        }

        Map<?, ?> service = selectService(instance, serviceName);
        if (service == NO_MATCH) {
            log.debug("Skipping NF instance {} — it does not expose service {}",
                    instance.get(NF_INSTANCE_ID), serviceName);
            return java.util.Optional.empty();
        }

        String apiRoot = resolveApiRoot(instance, service);
        if (Objects.isNull(apiRoot)) {
            log.debug("Skipping NF instance {} — no resolvable apiRoot", instance.get(NF_INSTANCE_ID));
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new DiscoveredNfInstance(
                stringOrNull(instance.get(NF_INSTANCE_ID)),
                apiRoot,
                Objects.isNull(service) ? null : stringOrNull(service.get("serviceName")),
                apiVersionOf(service),
                intOrDefault(service, instance, "priority", DiscoveredNfInstance.DEFAULT_PRIORITY),
                intOrDefault(service, instance, "capacity", 0),
                intOrDefault(service, instance, "load", 0)));
    }

    /**
     * Reads {@code versions[].apiVersionInUri}, the segment that goes into the resource URI.
     * A profile may advertise several versions; the first one is the service's current one.
     */
    private static String apiVersionOf(Map<?, ?> service) {
        if (Objects.nonNull(service)
                && service.get("versions") instanceof List<?> versions
                && !versions.isEmpty()
                && versions.get(0) instanceof Map<?, ?> version) {
            return stringOrNull(version.get("apiVersionInUri"));
        }
        return null;
    }

    /**
     * Picks the NF service matching {@code serviceName}.
     *
     * <p>An instance that advertises named services but none of the requested one does not
     * serve the caller, so it is rejected (by returning {@link #NO_MATCH}). An instance whose
     * services carry no {@code serviceName} at all is accepted on its first service: some NRFs
     * return reduced profiles, and dropping those would leave the requester with no target
     * even though the endpoint is usable.</p>
     */
    private static Map<?, ?> selectService(Map<?, ?> instance, String serviceName) {
        List<?> services = instance.get("nfServices") instanceof List<?> list ? list : List.of();
        Map<?, ?> first = null;
        boolean anyNamed = false;
        for (Object raw : services) {
            if (!(raw instanceof Map<?, ?> service)) {
                continue;
            }
            if (Objects.isNull(first)) {
                first = service;
            }
            String name = stringOrNull(service.get("serviceName"));
            if (Objects.nonNull(name)) {
                anyNamed = true;
                if (Objects.nonNull(serviceName) && serviceName.equalsIgnoreCase(name)) {
                    return service;
                }
            }
        }
        if (Objects.nonNull(serviceName) && anyNamed) {
            return NO_MATCH;
        }
        return first;
    }

    /**
     * Builds the apiRoot from the selected service, preferring an explicit {@code apiPrefix}
     * and otherwise composing {@code scheme://host[:port]} from the service's IP endpoints
     * or the instance-level FQDN/addresses.
     */
    private static String resolveApiRoot(Map<?, ?> instance, Map<?, ?> service) {
        if (Objects.nonNull(service)) {
            String prefix = stringOrNull(service.get("apiPrefix"));
            if (Objects.nonNull(prefix)) {
                return prefix;
            }
        }
        String scheme = schemeOf(service);
        String host = hostOf(instance, service);
        if (Objects.isNull(host)) {
            return null;
        }
        Integer port = portOf(service);
        return Objects.isNull(port) ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    private static String schemeOf(Map<?, ?> service) {
        String scheme = Objects.isNull(service) ? null : stringOrNull(service.get("scheme"));
        return Objects.isNull(scheme) ? DEFAULT_SCHEME : scheme.toLowerCase(Locale.ROOT);
    }

    private static String hostOf(Map<?, ?> instance, Map<?, ?> service) {
        if (Objects.nonNull(service)) {
            String serviceFqdn = stringOrNull(service.get("fqdn"));
            if (Objects.nonNull(serviceFqdn)) {
                return serviceFqdn;
            }
            Map<?, ?> endpoint = firstIpEndPoint(service);
            if (Objects.nonNull(endpoint)) {
                String host = firstNonNull(
                        stringOrNull(endpoint.get("fqdn")),
                        stringOrNull(endpoint.get("ipv4Address")));
                if (Objects.nonNull(host)) {
                    return host;
                }
            }
        }
        String fqdn = stringOrNull(instance.get("fqdn"));
        if (Objects.nonNull(fqdn)) {
            return fqdn;
        }
        if (instance.get("ipv4Addresses") instanceof List<?> addresses && !addresses.isEmpty()) {
            return stringOrNull(addresses.get(0));
        }
        return null;
    }

    private static Integer portOf(Map<?, ?> service) {
        Map<?, ?> endpoint = firstIpEndPoint(service);
        return Objects.isNull(endpoint) ? null : intOrNull(endpoint.get("port"));
    }

    private static Map<?, ?> firstIpEndPoint(Map<?, ?> service) {
        if (Objects.nonNull(service)
                && service.get("ipEndPoints") instanceof List<?> endpoints
                && !endpoints.isEmpty()
                && endpoints.get(0) instanceof Map<?, ?> endpoint) {
            return endpoint;
        }
        return null;
    }

    /** {@code validityPeriod} is expressed in seconds by TS 29.510. */
    private static Instant expiryOf(Map<String, Object> root, Instant now, Duration defaultTtl) {
        Integer validity = intOrNull(root.get("validityPeriod"));
        if (Objects.nonNull(validity) && validity > 0) {
            return now.plusSeconds(validity);
        }
        return Objects.isNull(defaultTtl) ? null : now.plus(defaultTtl);
    }

    private static int intOrDefault(Map<?, ?> service, Map<?, ?> instance, String key, int fallback) {
        Integer fromService = Objects.isNull(service) ? null : intOrNull(service.get(key));
        if (Objects.nonNull(fromService)) {
            return fromService;
        }
        Integer fromInstance = intOrNull(instance.get(key));
        return Objects.isNull(fromInstance) ? fallback : fromInstance;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonNull(String first, String second) {
        return Objects.isNull(first) ? second : first;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
