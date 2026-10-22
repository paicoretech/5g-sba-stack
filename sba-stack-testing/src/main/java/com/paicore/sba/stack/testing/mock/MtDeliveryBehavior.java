package com.paicore.sba.stack.testing.mock;

import java.time.Duration;

/**
 * Pluggable strategy that lets tests choreograph how the MT receiver responds:
 * always deliver, force a delay, return retryable errors, deliver-after-N
 * attempts, etc.
 */
@FunctionalInterface
public interface MtDeliveryBehavior {

    Decision decide(String supi, int attemptNumber);

    static MtDeliveryBehavior alwaysDeliver() {
        return (supi, n) -> Decision.delivered();
    }

    static MtDeliveryBehavior alwaysFail(int statusCode, String errorCode, String detail) {
        return (supi, n) -> Decision.failed(statusCode, errorCode, detail);
    }

    static MtDeliveryBehavior failFirstNAttempts(int n, int failStatus, String code) {
        return (supi, attempt) -> attempt <= n
                ? Decision.failed(failStatus, code, "Transient mock failure attempt " + attempt)
                : Decision.delivered();
    }

    static MtDeliveryBehavior delayBy(Duration delay) {
        return (supi, n) -> Decision.deliveredWithDelay(delay);
    }

    record Decision(boolean deliver, int statusCode, String errorCode,
                    String errorDetail, Duration delay) {
        public static Decision delivered() {
            return new Decision(true, 204, null, null, Duration.ZERO);
        }

        public static Decision deliveredWithDelay(Duration delay) {
            return new Decision(true, 204, null, null, delay);
        }

        public static Decision failed(int status, String code, String detail) {
            return new Decision(false, status, code, detail, Duration.ZERO);
        }
    }
}
