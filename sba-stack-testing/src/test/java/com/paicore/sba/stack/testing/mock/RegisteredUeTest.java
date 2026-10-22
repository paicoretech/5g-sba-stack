package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.testing.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage tests for the {@link RegisteredUe} Lombok-generated POJO. Only
 * the {@code @Builder} + {@code @Getter} bytecode exists for this class, but
 * JaCoCo still counts those instructions until they execute.
 */
class RegisteredUeTest {

    @Test
    @DisplayName("builder constructs an immutable snapshot and getters return the provided values")
    void builderRoundTrip() {
        Instant now = Instant.now();
        UeSmsContextData ctx = TestFixtures.defaultUeContext();
        String sessionId = "session-123";

        RegisteredUe ue = RegisteredUe.builder()
                .supi(TestFixtures.DEFAULT_SUPI)
                .context(ctx)
                .sessionId(sessionId)
                .registeredAt(now)
                .build();

        assertThat(ue.getSupi()).isEqualTo(TestFixtures.DEFAULT_SUPI);
        assertThat(ue.getContext()).isSameAs(ctx);
        assertThat(ue.getSessionId()).isEqualTo(sessionId);
        assertThat(ue.getRegisteredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("builder tolerates null fields — getters return null without throwing")
    void builderAllowsNulls() {
        RegisteredUe ue = RegisteredUe.builder().build();

        assertThat(ue.getSupi()).isNull();
        assertThat(ue.getContext()).isNull();
        assertThat(ue.getSessionId()).isNull();
        assertThat(ue.getRegisteredAt()).isNull();
    }
}
