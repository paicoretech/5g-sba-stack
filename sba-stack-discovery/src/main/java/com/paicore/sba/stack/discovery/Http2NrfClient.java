package com.paicore.sba.stack.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal HTTP/2-capable NRF discovery client per 3GPP TS29.510.
 * Issues a GET against
 * {@code {nrfBaseUri}/nnrf-disc/v1/nf-instances?target-nf-type=...&requester-nf-type=...}
 * and maps the {@code nfInstances} array to {@link NFProfile}s.
 */
@Slf4j
public class Http2NrfClient implements NrfClient {

    private final HttpClient http;
    private final NrfDiscoverySettings settings;

    public Http2NrfClient(NrfDiscoverySettings settings) {
        this(settings, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(settings.getRequestTimeout())
                .build());
    }

    Http2NrfClient(NrfDiscoverySettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    @Override
    public List<NFProfile> discoverInstances(NFType targetNfType) {
        URI uri = buildDiscoveryUri(targetNfType);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(settings.getRequestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("NRF discovery for {} returned HTTP {}", targetNfType, response.statusCode());
                return List.of();
            }
            return parseInstances(response.body());
        } catch (IOException e) {
            log.warn("NRF discovery for {} failed: {}", targetNfType, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // S1075: the "/" tokens here are not OS path delimiters but URL path
    // separators per RFC 3986; they are immutable by the HTTP standard.
    @SuppressWarnings("java:S1075")
    private URI buildDiscoveryUri(NFType targetNfType) {
        String query = "target-nf-type=" + URLEncoder.encode(targetNfType.name(), StandardCharsets.UTF_8)
                + "&requester-nf-type=" + URLEncoder.encode(settings.getRequesterNfType(), StandardCharsets.UTF_8);
        String base = settings.getNrfBaseUri().toString();
        String path = settings.getDiscoveryPath();
        if (base.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(base + path + "?" + query);
    }

    private List<NFProfile> parseInstances(String body) {
        Map<String, Object> root = SdkConverter.stringToObject(body, new TypeReference<>() {});
        Object raw = root.get("nfInstances");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> toProfile((Map<?, ?>) m))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<NFProfile> toProfile(Map<?, ?> instance) {
        String instanceId = stringOrNull(instance.get("nfInstanceId"));
        String typeRaw = stringOrNull(instance.get("nfType"));
        if (instanceId == null || typeRaw == null) {
            return Optional.empty();
        }
        NFType nfType;
        try {
            nfType = NFType.valueOf(typeRaw);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping NF profile with unknown nfType '{}'", typeRaw);
            return Optional.empty();
        }
        return Optional.of(NFProfile.builder()
                .nfInstanceId(instanceId)
                .nfType(nfType)
                .apiRoot(extractApiRoot(instance))
                .build());
    }

    private static String extractApiRoot(Map<?, ?> instance) {
        String schema = "http://";
        Object services = instance.get("nfServices");
        if (services instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> svc) {
            String prefix = stringOrNull(svc.get("apiPrefix"));
            if (prefix != null) {
                return prefix;
            }
        }
        String fqdn = stringOrNull(instance.get("fqdn"));
        if (fqdn != null) {
            return schema + fqdn;
        }
        Object ipv4 = instance.get("ipv4Addresses");
        if (ipv4 instanceof List<?> list && !list.isEmpty()) {
            return schema + list.getFirst();
        }
        return null;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }
}
