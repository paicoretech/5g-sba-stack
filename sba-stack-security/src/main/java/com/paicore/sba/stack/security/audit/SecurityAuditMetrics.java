package com.paicore.sba.stack.security.audit;

public interface SecurityAuditMetrics {

    void incrementAuthSuccess();

    void incrementAuthFailure();

    SecurityAuditMetrics NOOP = new SecurityAuditMetrics() {
        @Override
        public void incrementAuthSuccess() {
            // intentionally empty: NOOP implementation
        }

        @Override
        public void incrementAuthFailure() {
            // intentionally empty: NOOP implementation
        }
    };
}
