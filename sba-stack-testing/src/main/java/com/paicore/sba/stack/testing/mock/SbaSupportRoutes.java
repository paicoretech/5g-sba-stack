package com.paicore.sba.stack.testing.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.paicore.sba.stack.api.nnrf.NFProfile;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.fixtures.JwtFixture;
import com.paicore.sba.stack.utils.SdkConverter;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Protocol-neutral routing logic shared by {@link MockSbaCoreServer} (JDK
 * {@code HttpServer}, HTTP/1.1) and {@link MockSbaH2cServer} (Netty, HTTP/2).
 * Both servers used to carry their own copy of the NRF discovery / NRF NFM
 * registration / OAuth2 jwks+token routes — this class consolidates that
 * logic and emits an {@link HttpResult}, which each server then renders into
 * its native response type.
 *
 * <p>The class is stateless except for the {@link AtomicReference} holding the
 * NF profile list, which the caller owns and passes in via the constructor —
 * so the mock servers retain control over their registry without leaking
 * routing code into themselves.
 */
public final class SbaSupportRoutes {

    @SuppressWarnings("java:S1075")
    public static final String NRF_DISCOVERY_PATH = "/nnrf-disc/v1/nf-instances";
    @SuppressWarnings("java:S1075")
    public static final String NRF_NFM_PATH_PREFIX = "/nnrf-nfm/v1/nf-instances/";
    public static final String JWKS_PATH = "/oauth2/jwks";
    public static final String TOKEN_PATH = "/oauth2/token";

    private static final String MOCK_NOT_FOUND = "MOCK-4040";

    private final AtomicReference<List<NFProfile>> nfProfiles;
    private final JwtFixture jwt;

    public SbaSupportRoutes(AtomicReference<List<NFProfile>> nfProfiles, JwtFixture jwt) {
        this.nfProfiles = nfProfiles;
        this.jwt = jwt;
    }

    // ---------------------------------------------------------------- NRF discovery

    /** GET {@code /nnrf-disc/v1/nf-instances?target-nf-type=...} per TS29.510. */
    public HttpResult nrfDiscovery(String method, String query) {
        if (!"GET".equalsIgnoreCase(method)) {
            return HttpResult.methodNotAllowed("Use GET to discover NF instances");
        }
        String targetType = parseQuery(query).get("target-nf-type");
        List<Map<String, Object>> instances = new ArrayList<>();
        for (NFProfile p : nfProfiles.get()) {
            if (targetType != null && !targetType.equalsIgnoreCase(p.getNfType().name())) {
                continue;
            }
            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("nfInstanceId", p.getNfInstanceId());
            instance.put("nfType", p.getNfType().name());
            instance.put("fqdn", URI.create(p.getApiRoot()).getHost());
            Map<String, Object> svc = new LinkedHashMap<>();
            svc.put("serviceName", defaultServiceName(p.getNfType()));
            svc.put("apiPrefix", p.getApiRoot());
            instance.put("nfServices", List.of(svc));
            instances.add(instance);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("validityPeriod", 3600);
        response.put("nfInstances", instances);
        return HttpResult.ok(response);
    }

    // ---------------------------------------------------------------- NRF NFM register

    /**
     * PUT or POST {@code /nnrf-nfm/v1/nf-instances/{instanceId}}.
     * {@code defaultApiRoot} is used when the registration body omits its own
     * apiRoot (typical for tests that just want a profile present).
     */
    public HttpResult nrfRegistration(String method, String path, byte[] body, String defaultApiRoot) {
        if (!path.startsWith(NRF_NFM_PATH_PREFIX)) {
            return HttpResult.notFound(MOCK_NOT_FOUND, "Unknown NRF NFM path");
        }
        String instanceId = path.substring(NRF_NFM_PATH_PREFIX.length());
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            return HttpResult.methodNotAllowed("Use PUT to register NF instance");
        }
        Map<String, Object> payload = SdkConverter.stringToObject(
                new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8),
                new TypeReference<>() {});
        String typeRaw = (String) payload.getOrDefault("nfType", NFType.SMSF.name());
        NFType nfType;
        try {
            nfType = NFType.valueOf(typeRaw);
        } catch (IllegalArgumentException e) {
            return HttpResult.badRequest("MOCK-4001", "Unknown nfType: " + typeRaw);
        }
        List<NFProfile> current = new ArrayList<>(nfProfiles.get());
        current.removeIf(p -> p.getNfInstanceId().equals(instanceId));
        current.add(NFProfile.builder()
                .nfInstanceId(instanceId)
                .nfType(nfType)
                .apiRoot(String.valueOf(payload.getOrDefault("apiRoot", defaultApiRoot)))
                .build());
        nfProfiles.set(List.copyOf(current));
        payload.put("nfInstanceId", instanceId);
        return HttpResult.created(payload);
    }

    // ---------------------------------------------------------------- OAuth2

    /** GET {@link #JWKS_PATH} or POST {@link #TOKEN_PATH}. */
    public HttpResult oauth2(String method, String path) {
        if (JWKS_PATH.equals(path)) {
            if (!"GET".equalsIgnoreCase(method)) {
                return HttpResult.methodNotAllowed("Use GET");
            }
            return HttpResult.ok(jwt.jwks());
        }
        if (TOKEN_PATH.equals(path)) {
            if (!"POST".equalsIgnoreCase(method)) {
                return HttpResult.methodNotAllowed("Use POST");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", jwt.issueToken("nf-instance", Duration.ofMinutes(5), "smsf.api"));
            body.put("token_type", "Bearer");
            body.put("expires_in", 300);
            return HttpResult.ok(body);
        }
        return HttpResult.notFound(MOCK_NOT_FOUND, "Unknown OAuth2 path: " + path);
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static String defaultServiceName(NFType type) {
        return switch (type) {
            case UDM -> "nudm-sdm";
            case AMF -> "namf-comm";
            case SMSF -> "nsmsf-sms";
            case NRF -> "nnrf-disc";
            case AUSF -> "nausf-auth";
            case PCF -> "npcf-policyauth";
            case SCP -> "nscp-discovery";
            case SMF -> "nsmf-pdusession";
        };
    }
}
