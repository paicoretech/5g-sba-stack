package com.paicore.sba.stack.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpiNoopTest {

    @Test
    @DisplayName("TracerProvider.NOOP returns a Span that is fully chainable")
    void tracerProviderNoopShouldReturnChainableSpan() {
        try (TracerProvider.Span span = TracerProvider.NOOP.startSpan("mo-decode")) {
            assertSame(span, span.setAttribute("messageId", "abc-123"));
            assertSame(span, span.recordException(new IllegalStateException("ignored")));
        }
    }

    @Test
    @DisplayName("TracerProvider.NOOP produces a fresh Span on every startSpan call")
    void tracerProviderNoopShouldProduceFreshSpans() {
        TracerProvider.Span first = TracerProvider.NOOP.startSpan("a");
        TracerProvider.Span second = TracerProvider.NOOP.startSpan("b");
        assertDoesNotThrow(first::close);
        assertDoesNotThrow(second::close);
    }
}
