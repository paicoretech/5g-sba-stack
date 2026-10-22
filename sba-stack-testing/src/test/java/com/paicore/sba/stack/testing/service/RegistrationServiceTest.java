package com.paicore.sba.stack.testing.service;

import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.fixtures.UeContextFixture;
import com.paicore.sba.stack.testing.service.model.RegistrationResult;
import com.paicore.sba.stack.testing.service.model.UeSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the SMSF registration business logic — no HTTP, no
 * Netty, no JWT. Exercises only the rules a real SMSF must enforce when
 * receiving a TS29.540 UEContextCreate / UEContextDeletion.
 */
class RegistrationServiceTest {

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService();
    }

    @Test
    @DisplayName("register stores the UE context and mints a fresh session id")
    void registerHappyPath() {
        RegistrationResult result = service.register(TestFixtures.DEFAULT_SUPI,
                TestFixtures.defaultUeContext());

        assertThat(result.accepted()).isTrue();
        UeSession session = result.session();
        assertThat(session.supi()).isEqualTo(TestFixtures.DEFAULT_SUPI);
        assertThat(session.sessionId()).isNotBlank();
        assertThat(session.registeredAt()).isNotNull();
        assertThat(service.isRegistered(TestFixtures.DEFAULT_SUPI)).isTrue();
        assertThat(service.registeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-registering the same SUPI replaces the prior session")
    void reRegistrationOverwritesSession() {
        UeSession first = service.register(TestFixtures.DEFAULT_SUPI,
                TestFixtures.defaultUeContext()).session();

        UeSession second = service.register(TestFixtures.DEFAULT_SUPI,
                TestFixtures.defaultUeContext()).session();

        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(service.registeredCount()).isEqualTo(1);
        assertThat(service.findBySupi(TestFixtures.DEFAULT_SUPI))
                .map(UeSession::sessionId)
                .hasValue(second.sessionId());
    }

    @Test
    @DisplayName("register rejects when URL SUPI does not match the body's supi")
    void mismatchedSupiIsRejected() {
        UeSmsContextData wrong = UeContextFixture.contextFor("imsi-460000000000999");

        RegistrationResult result = service.register(TestFixtures.DEFAULT_SUPI, wrong);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).startsWith("SMSF-");
        assertThat(service.isRegistered(TestFixtures.DEFAULT_SUPI)).isFalse();
    }

    @Test
    @DisplayName("register rejects an invalid SUPI (not imsi-prefix)")
    void invalidSupiIsRejected() {
        RegistrationResult result = service.register("not-a-supi",
                UeContextFixture.contextFor("not-a-supi"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SMSF-4001"); // INVALID_SUPI
    }

    @Test
    @DisplayName("deregister returns the prior session and removes it from the registry")
    void deregisterRemovesSession() {
        UeSession session = service.register(TestFixtures.DEFAULT_SUPI,
                TestFixtures.defaultUeContext()).session();

        Optional<UeSession> removed = service.deregister(TestFixtures.DEFAULT_SUPI);

        assertThat(removed).map(UeSession::sessionId).hasValue(session.sessionId());
        assertThat(service.isRegistered(TestFixtures.DEFAULT_SUPI)).isFalse();
        assertThat(service.registeredCount()).isZero();
    }

    @Test
    @DisplayName("deregister on an unknown SUPI returns Optional.empty")
    void deregisterUnknownSupi() {
        assertThat(service.deregister("imsi-460000000000404")).isEmpty();
    }
}
