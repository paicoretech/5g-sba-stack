package com.paicore.sba.stack.api;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.dto.DeliveryReport;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.spi.TransportProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbaStackImplTest {

    private final Http2Config http2 = new Http2Config() {};
    private final SecurityConfig security = new SecurityConfig() {};
    private final DiscoveryConfig discovery = new DiscoveryConfig() {};

    private SbaStackImpl freshImpl() {
        return new SbaStackImpl(http2, security, discovery);
    }

    @Test
    @DisplayName("registerUe throws UnsupportedOperationException by contract")
    void registerUeUnsupported() {
        try (SbaStackImpl impl = freshImpl()) {
            RegistrationRequest req = new RegistrationRequest();
            assertThatThrownBy(() -> impl.registerUe(req))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("downstream SMSF kit");
        }
    }

    @Test
    @DisplayName("deregisterUe throws UnsupportedOperationException by contract")
    void deregisterUeUnsupported() {
        try (SbaStackImpl impl = freshImpl()) {
            String supi = "imsi-460000000000001";
            assertThatThrownBy(() -> impl.deregisterUe(supi))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("receiveMoMessage throws UnsupportedOperationException by contract")
    void receiveMoUnsupported() {
        try (SbaStackImpl impl = freshImpl()) {
            MoMessageRequest req = new MoMessageRequest();
            assertThatThrownBy(() -> impl.receiveMoMessage(req))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("processDeliveryReport is a default no-op")
    void processDeliveryReportIsNoOp() {
        try (SbaStackImpl impl = freshImpl()) {
            DeliveryReport dr = new DeliveryReport();
            impl.processDeliveryReport(dr);
            assertThat(impl.getN20Client()).isNotNull();
        }
    }

    @Test
    @DisplayName("sendMtMessage throws IllegalStateException when no transport is registered")
    void sendMtRequiresTransport() throws Exception {
        try (SbaStackImpl impl = freshImpl()) {
            replaceTransport(impl, null);
            MtMessageRequest req = new MtMessageRequest();
            assertThatThrownBy(() -> impl.sendMtMessage(req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No TransportProvider");
        }
    }

    @Test
    @DisplayName("sendMtMessage delegates to transport and returns the future result")
    void sendMtDelegatesToTransport() throws Exception {
        SbaStackImpl impl = freshImpl();
        MtResponse expected = new MtResponse();
        RecordingTransport tx = new RecordingTransport(expected, null);
        replaceTransport(impl, tx);
        try {
            MtResponse got = impl.sendMtMessage(new MtMessageRequest());
            assertThat(got).isSameAs(expected);
            assertThat(tx.dispatchCount.get()).isEqualTo(1);
        } finally {
            impl.close();
            assertThat(tx.closed.get()).isTrue();
        }
    }

    @Test
    @DisplayName("sendMtMessage wraps transport failures in IllegalStateException")
    void sendMtWrapsFailures() throws Exception {
        SbaStackImpl impl = freshImpl();
        RecordingTransport tx = new RecordingTransport(null, new RuntimeException("boom"));
        replaceTransport(impl, tx);
        try {
            MtMessageRequest req = new MtMessageRequest();
            assertThatThrownBy(() -> impl.sendMtMessage(req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MT dispatch failed");
        } finally {
            impl.close();
        }
    }

    @Test
    @DisplayName("close swallows transport exceptions (best-effort)")
    void closeSwallowsTransportFailure() throws Exception {
        SbaStackImpl impl = freshImpl();
        RecordingTransport tx = new RecordingTransport(null, null);
        tx.throwOnClose = true;
        replaceTransport(impl, tx);
        impl.close();
        assertThat(tx.closeAttempted.get()).isTrue();
    }

    @Test
    @DisplayName("close is a no-op when transport is null")
    void closeNoOpWithoutTransport() throws Exception {
        SbaStackImpl impl = freshImpl();
        replaceTransport(impl, null);
        impl.close();
        assertThat(impl.getN20Client()).isNotNull();
    }

    @Test
    @DisplayName("getNrfClient falls back to an empty discovery client")
    void nrfClientReturnsEmptyList() {
        try (SbaStackImpl impl = freshImpl()) {
            assertThat(impl.getNrfClient().discoverInstances(NFType.UDM)).isEmpty();
        }
    }

    private static void replaceTransport(SbaStackImpl impl, TransportProvider tx) throws Exception {
        Field f = SbaStackImpl.class.getDeclaredField("transport");
        f.setAccessible(true);
        f.set(impl, tx);
    }

    /** Minimal transport double — counts invocations and lets us flip behaviour. */
    private static final class RecordingTransport implements TransportProvider {
        final MtResponse response;
        final RuntimeException failure;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean closeAttempted = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger dispatchCount = new java.util.concurrent.atomic.AtomicInteger();
        boolean throwOnClose;

        RecordingTransport(MtResponse response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override public void start() { /* no-op */ }

        @Override
        public CompletableFuture<MtResponse> dispatchMt(MtMessageRequest request) {
            dispatchCount.incrementAndGet();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
            closeAttempted.set(true);
            if (throwOnClose) {
                throw new IllegalStateException("close failed");
            }
            closed.set(true);
        }
    }
}
