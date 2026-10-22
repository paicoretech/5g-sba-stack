package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;

import java.util.concurrent.CompletableFuture;

/**
 * SPI implemented by transport kits (e.g. sba-stack-http2) so the core stack
 * can dispatch outbound N-messages without depending on a concrete transport.
 */
public interface TransportProvider extends AutoCloseable {

    void start();

    CompletableFuture<MtResponse> dispatchMt(MtMessageRequest request);

    @Override
    void close();
}
