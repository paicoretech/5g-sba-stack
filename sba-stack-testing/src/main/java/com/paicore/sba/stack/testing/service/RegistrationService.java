package com.paicore.sba.stack.testing.service;

import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.testing.service.model.RegistrationResult;
import com.paicore.sba.stack.testing.service.model.UeSession;
import com.paicore.sba.stack.utils.ErrorCodes;
import com.paicore.sba.stack.validation.SupiValidator;
import com.paicore.sba.stack.validation.UeContextValidator;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory UE registry — the SMSF-side state machine for registered
 * subscribers, with no HTTP coupling. Implements the business rules that
 * 3GPP TS29.540 {@code POST /ue-contexts/{supi}} / {@code DELETE
 * /ue-contexts/{supi}} need to honour:
 *
 * <ul>
 *   <li>SUPI in the URL must match the body's {@code ueContext.supi}.</li>
 *   <li>The supplied {@link UeSmsContextData} must pass
 *       {@link UeContextValidator} (PEI Luhn, SUPI prefix, accessType).</li>
 *   <li>Each accepted registration mints a fresh session id; a re-registration
 *       overwrites the prior session.</li>
 *   <li>Deregistration removes the entry (returns the prior session for audit).</li>
 * </ul>
 */
@Slf4j
public class RegistrationService {

    private final ConcurrentMap<String, UeSession> sessions = new ConcurrentHashMap<>();

    public RegistrationResult register(String supi, UeSmsContextData context) {
        try {
            SupiValidator.requireValid(supi);
            UeContextValidator.requireValid(context);
        } catch (SdkException e) {
            return RegistrationResult.rejected(e.getCode(), e.getDetails());
        }
        if (!supi.equals(context.getSupi())) {
            return RegistrationResult.rejected(ErrorCodes.INVALID_REGISTRATION_PAYLOAD,
                    "SUPI in URL does not match payload.supi");
        }
        UeSession session = new UeSession(supi, context,
                UUID.randomUUID().toString(), Instant.now());
        sessions.put(supi, session);
        log.info("[reg-svc] registered supi={} sessionId={}", supi, session.sessionId());
        return RegistrationResult.accepted(session);
    }

    public Optional<UeSession> deregister(String supi) {
        UeSession removed = sessions.remove(supi);
        if (removed != null) {
            log.info("[reg-svc] deregistered supi={} sessionId={}", supi, removed.sessionId());
        }
        return Optional.ofNullable(removed);
    }

    public Optional<UeSession> findBySupi(String supi) {
        return Optional.ofNullable(sessions.get(supi));
    }

    public boolean isRegistered(String supi) {
        return sessions.containsKey(supi);
    }

    public int registeredCount() {
        return sessions.size();
    }

    public Collection<UeSession> all() {
        return sessions.values();
    }

    public void clear() {
        sessions.clear();
    }
}
