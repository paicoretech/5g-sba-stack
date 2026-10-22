package com.paicore.sba.stack.testing.controller;

import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.service.RegistrationService;
import com.paicore.sba.stack.testing.service.model.RegistrationResult;
import com.paicore.sba.stack.testing.service.model.UeSession;
import com.paicore.sba.stack.utils.ErrorCodes;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Glue between HTTP wire framing and the {@link RegistrationService}.
 * Responsibilities — what an operator-grade SMSF actually has to do at the
 * 3GPP TS29.540 {@code POST /ue-contexts/{supi}} (and {@code DELETE …})
 * endpoint:
 *
 * <ol>
 *   <li>Parse the JSON body into a typed {@link UeSmsContextData}
 *       (tolerating both the wrapped {@link RegistrationRequest} form and
 *       the bare-context form the spec actually shows in §6.3.2).</li>
 *   <li>Hand the decoded objects to {@link RegistrationService} — the
 *       service is fully HTTP-unaware.</li>
 *   <li>Map the service's {@link RegistrationResult} back into an
 *       {@link HttpResult}: 201 + JSON body on success, 4xx + problem
 *       document on failure.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService service;

    public HttpResult register(String supi, byte[] body) {
        UeSmsContextData ctx;
        String correlationId;
        try {
            RegistrationRequest wrapper = SdkConverter.stringToObject(
                    new String(body, StandardCharsets.UTF_8), RegistrationRequest.class);
            correlationId = wrapper.getCorrelationId();
            ctx = wrapper.getUeContext();
            if (ctx == null) {
                // Spec example body is a bare UeSmsContextData — try that next.
                ctx = SdkConverter.stringToObject(
                        new String(body, StandardCharsets.UTF_8), UeSmsContextData.class);
            }
        } catch (SdkException e) {
            return HttpResult.badRequest(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "Cannot parse registration JSON: " + e.getDetails());
        }

        RegistrationResult result = service.register(supi, ctx);
        if (!result.accepted()) {
            log.info("[reg-ctrl] registration rejected supi={} code={} detail={}",
                    supi, result.errorCode(), result.errorDetail());
            return HttpResult.badRequest(result.errorCode(), result.errorDetail());
        }
        UeSession session = result.session();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("correlationId", correlationId);
        resp.put("sessionId", session.sessionId());
        resp.put("supi", session.supi());
        resp.put("accepted", true);
        log.info("[reg-ctrl] registration accepted supi={} sessionId={}",
                supi, session.sessionId());
        return HttpResult.created(resp);
    }

    public HttpResult deregister(String supi) {
        Optional<UeSession> removed = service.deregister(supi);
        if (removed.isEmpty()) {
            return HttpResult.notFound(ErrorCodes.UE_NOT_REGISTERED,
                    "No active session for SUPI " + supi);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("supi", removed.get().supi());
        resp.put("sessionId", removed.get().sessionId());
        resp.put("deregisteredAt", java.time.Instant.now().toString());
        return HttpResult.ok(resp);
    }
}
