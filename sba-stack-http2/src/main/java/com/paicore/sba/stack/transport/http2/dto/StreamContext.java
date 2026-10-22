package com.paicore.sba.stack.transport.http2.dto;

import io.netty.handler.codec.http2.Http2FrameStream;
import lombok.Generated;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

@Getter
@Generated
public class StreamContext {

    private final Http2FrameStream stream;
    private final String correlationId;
    private final long startNanos;
    private final CompletableFuture<Http2Response> future;

    public StreamContext(Http2FrameStream stream, String correlationId) {
        this.stream = stream;
        this.correlationId = correlationId;
        this.startNanos = System.nanoTime();
        this.future = new CompletableFuture<>();
    }

    public int getStreamId() {
        return stream.id();
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }
}
