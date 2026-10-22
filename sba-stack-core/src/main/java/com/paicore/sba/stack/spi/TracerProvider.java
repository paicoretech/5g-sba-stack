package com.paicore.sba.stack.spi;

/**
 * SPI implemented by operator-provided plugins for distributed tracing. Returned
 * span handle is closeable so callers can use try-with-resources around a
 * unit of work.
 */
public interface TracerProvider {

    Span startSpan(String name);

    interface Span extends AutoCloseable {

        Span setAttribute(String key, String value);

        Span recordException(Throwable error);

        @Override
        void close();
    }

    TracerProvider NOOP = name -> new Span() {
        @Override public Span setAttribute(String key, String value) { return this; }
        @Override public Span recordException(Throwable error) { return this; }
        @Override public void close() { /* NOOP tracer — nothing to release. */ }
    };
}
