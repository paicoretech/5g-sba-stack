package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.dto.Http2Request;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyHttp2ClientTest {

    @Test
    void rejectsMissingHost() {
        Http2ClientSettings empty = Http2ClientSettings.builder().build();
        assertThrows(Http2TransportException.class, () -> new NettyHttp2Client(empty));
    }

    @Test
    void sendFailsWhenNotConnected() {
        Http2ClientSettings cfg = Http2ClientSettings.builder().host("localhost").port(0).build();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (NettyHttp2Client client = new NettyHttp2Client(cfg, group, false)) {
            assertFalse(client.isConnected());
            CompletableFuture<Http2Response> future = client.send(
                    Http2Request.builder().method("POST").path("/").build());
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
        } catch (Exception e) {
            // tolerate executor teardown noise
        } finally {
            group.shutdownGracefully().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }
}
