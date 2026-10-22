package com.paicore.sba.stack.testing.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises every factory and {@link MtDeliveryBehavior.Decision} constructor
 * so the {@link MtDeliveryBehavior} interface reaches 100% instruction coverage.
 */
class MtDeliveryBehaviorTest {

    @Test
    @DisplayName("alwaysDeliver returns DELIVERED with zero delay for every attempt")
    void alwaysDeliverProducesDelivered() {
        MtDeliveryBehavior behavior = MtDeliveryBehavior.alwaysDeliver();

        MtDeliveryBehavior.Decision d1 = behavior.decide("imsi-1", 1);
        MtDeliveryBehavior.Decision d99 = behavior.decide("imsi-2", 99);

        assertThat(d1.deliver()).isTrue();
        assertThat(d1.statusCode()).isEqualTo(204);
        assertThat(d1.errorCode()).isNull();
        assertThat(d1.errorDetail()).isNull();
        assertThat(d1.delay()).isEqualTo(Duration.ZERO);
        assertThat(d99.deliver()).isTrue();
    }

    @Test
    @DisplayName("alwaysFail returns the configured status/code/detail")
    void alwaysFailReturnsConfiguredFailure() {
        MtDeliveryBehavior behavior = MtDeliveryBehavior.alwaysFail(503, "ERR-503", "transient");

        MtDeliveryBehavior.Decision d = behavior.decide("imsi-1", 1);

        assertThat(d.deliver()).isFalse();
        assertThat(d.statusCode()).isEqualTo(503);
        assertThat(d.errorCode()).isEqualTo("ERR-503");
        assertThat(d.errorDetail()).isEqualTo("transient");
        assertThat(d.delay()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("failFirstNAttempts fails for attempts 1..N and delivers from N+1")
    void failFirstNAttemptsThenDeliver() {
        MtDeliveryBehavior behavior = MtDeliveryBehavior.failFirstNAttempts(2, 500, "ERR");

        MtDeliveryBehavior.Decision a1 = behavior.decide("supi", 1);
        MtDeliveryBehavior.Decision a2 = behavior.decide("supi", 2);
        MtDeliveryBehavior.Decision a3 = behavior.decide("supi", 3);

        assertThat(a1.deliver()).isFalse();
        assertThat(a1.statusCode()).isEqualTo(500);
        assertThat(a1.errorCode()).isEqualTo("ERR");
        assertThat(a1.errorDetail()).contains("attempt 1");
        assertThat(a2.deliver()).isFalse();
        assertThat(a2.errorDetail()).contains("attempt 2");
        assertThat(a3.deliver()).isTrue();
    }

    @Test
    @DisplayName("delayBy returns DELIVERED with the configured delay duration")
    void delayByProducesDelay() {
        Duration delay = Duration.ofMillis(75);
        MtDeliveryBehavior behavior = MtDeliveryBehavior.delayBy(delay);

        MtDeliveryBehavior.Decision d = behavior.decide("supi", 1);

        assertThat(d.deliver()).isTrue();
        assertThat(d.statusCode()).isEqualTo(204);
        assertThat(d.delay()).isEqualTo(delay);
    }

    @Test
    @DisplayName("Decision factories produce the expected immutable record values")
    void decisionFactoriesPopulateAllFields() {
        MtDeliveryBehavior.Decision delivered = MtDeliveryBehavior.Decision.delivered();
        MtDeliveryBehavior.Decision delayed = MtDeliveryBehavior.Decision
                .deliveredWithDelay(Duration.ofMillis(10));
        MtDeliveryBehavior.Decision failed = MtDeliveryBehavior.Decision
                .failed(429, "TOO_MANY", "rate-limited");

        assertThat(delivered.deliver()).isTrue();
        assertThat(delivered.statusCode()).isEqualTo(204);
        assertThat(delayed.delay().toMillis()).isEqualTo(10L);
        assertThat(failed.deliver()).isFalse();
        assertThat(failed.statusCode()).isEqualTo(429);
        assertThat(failed.errorCode()).isEqualTo("TOO_MANY");
        assertThat(failed.errorDetail()).isEqualTo("rate-limited");
    }
}
