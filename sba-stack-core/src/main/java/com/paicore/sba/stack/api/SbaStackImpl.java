package com.paicore.sba.stack.api;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.api.n20.DefaultN20Client;
import com.paicore.sba.stack.api.n20.N20Client;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.dto.DeliveryReport;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.dto.RegistrationResponse;
import com.paicore.sba.stack.spi.NrfClientFactory;
import com.paicore.sba.stack.spi.SecurityProviderFactory;
import com.paicore.sba.stack.spi.TransportProvider;
import com.paicore.sba.stack.spi.TransportProviderFactory;
import com.paicore.sba.stack.utils.SdkConstants;

import java.util.Collections;
import java.util.Optional;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SbaStackImpl implements SbaStack {

    private final TransportProvider transport;
    private final NrfClient nrfClient;
    private final N20Client n20Client;

    public SbaStackImpl(Http2Config http2Cfg, SecurityConfig securityCfg, DiscoveryConfig discoveryCfg) {
        this.transport = loadFirst(TransportProviderFactory.class)
                .map(factory -> factory.create(http2Cfg))
                .orElse(null);
        // Eagerly resolve a SecurityProvider so SPI implementations can warm up
        // (key material, JWK fetches) at stack-build time. We don't retain it
        // here — security wiring is per-flow and lives in the kits.
        loadFirst(SecurityProviderFactory.class).ifPresent(factory -> factory.create(securityCfg));
        this.nrfClient = loadFirst(NrfClientFactory.class)
                .map(factory -> factory.create(discoveryCfg))
                .filter(java.util.Objects::nonNull)
                .orElseGet(this::emptyNrfClient);
        this.n20Client = new DefaultN20Client();
        if (transport != null) {
            transport.start();
        }
    }

    private static <T> Optional<T> loadFirst(Class<T> spi) {
        return ServiceLoader.load(spi).findFirst();
    }

    private NrfClient emptyNrfClient() {
        return (NFType type) -> Collections.emptyList();
    }

    @Override
    public N20Client getN20Client() {
        return n20Client;
    }

    @Override
    public NrfClient getNrfClient() {
        return nrfClient;
    }

    @Override
    public String version() {
        return SdkConstants.SDK_VERSION;
    }

    @Override
    public RegistrationResponse registerUe(RegistrationRequest request) {
        throw new UnsupportedOperationException("registerUe: provided by a downstream SMSF kit");
    }

    @Override
    public void deregisterUe(String supi) {
        throw new UnsupportedOperationException("deregisterUe: provided by a downstream SMSF kit");
    }

    @Override
    public MtResponse sendMtMessage(MtMessageRequest request) {
        if (transport == null) {
            throw new IllegalStateException("No TransportProvider registered");
        }
        try {
            return transport.dispatchMt(request).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MT dispatch interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("MT dispatch failed", e.getCause() != null ? e.getCause() : e);
        }
    }

    @Override
    public MoResponse receiveMoMessage(MoMessageRequest request) {
        throw new UnsupportedOperationException("MO receive belongs in the SMSF application layer");
    }

    @Override
    public void processDeliveryReport(DeliveryReport report) {
        // No-op default: applications wire their DLR handler via plugin/SPI.
    }

    @Override
    public void close() {
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception e) {
                log.debug("Best-effort transport close raised: {}", e.toString());
            }
        }
    }
}
