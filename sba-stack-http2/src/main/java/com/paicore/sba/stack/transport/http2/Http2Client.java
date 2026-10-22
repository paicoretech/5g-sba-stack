package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;

import java.util.concurrent.CompletableFuture;

public interface Http2Client extends AutoCloseable {

    void connect();

    boolean isConnected();

    CompletableFuture<Http2Response> send(Http2Request request);

    @Override
    void close();
}
