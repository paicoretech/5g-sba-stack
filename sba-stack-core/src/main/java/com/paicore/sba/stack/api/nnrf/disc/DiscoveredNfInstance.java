package com.paicore.sba.stack.api.nnrf.disc;

/**
 * A single NF instance returned by {@code Nnrf_NFDiscovery}, reduced to what a
 * requester needs in order to open a connection, build the resource URI and rank candidates.
 *
 * <p>{@code serviceName} and {@code apiVersion} are what let the caller compose
 * {@code {apiRoot}/{apiName}/{apiVersion}/...} as TS 29.501 clause 4.4.1 prescribes, instead
 * of hard-coding an API version the target may not serve.</p>
 *
 * @param nfInstanceId the target's NF instance id
 * @param apiRoot      resolved apiRoot ({@code scheme://host[:port][/prefix]})
 * @param serviceName  advertised service name, e.g. {@code nudm-uecm}; {@code null} when absent
 * @param apiVersion   advertised {@code apiVersionInUri}, e.g. {@code v1}; {@code null} when absent
 * @param priority     lower is preferred (TS 29.510 {@code priority}); {@link #DEFAULT_PRIORITY} when absent
 * @param capacity     higher is preferred (TS 29.510 {@code capacity}); {@code 0} when absent
 * @param load         lower is preferred (TS 29.510 {@code load}); {@code 0} when absent
 */
public record DiscoveredNfInstance(String nfInstanceId,
                                   String apiRoot,
                                   String serviceName,
                                   String apiVersion,
                                   int priority,
                                   int capacity,
                                   int load) {

    /** Applied when the NF profile carries no {@code priority}, so unranked instances sort last. */
    public static final int DEFAULT_PRIORITY = Integer.MAX_VALUE;
}
