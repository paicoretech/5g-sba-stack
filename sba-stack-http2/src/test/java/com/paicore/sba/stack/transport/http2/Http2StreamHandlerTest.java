package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import com.paicore.sba.stack.transport.http2.dto.StreamContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Http2StreamHandlerTest {

    private Http2StreamHandler handler;

    @BeforeEach
    void setup() {
        handler = new Http2StreamHandler();
    }

    private static Http2FrameStream stream(int id) {
        return new Http2FrameStream() {
            @Override
            public int id() {
                return id;
            }

            @Override
            public Http2Stream.State state() {
                return Http2Stream.State.OPEN;
            }
        };
    }

    private void dispatch(Http2Frame frame) throws Exception {
        handler.channelRead(mock(ChannelHandlerContext.class), frame);
    }

    @Test
    void registerAddsActiveStream() {
        StreamContext ctx = new StreamContext(stream(3), "corr-1");
        handler.register(ctx);
        assertThat(handler.activeStreams()).isEqualTo(1);
    }

    @Test
    void headersWithoutEndStreamKeepsStreamActive() throws Exception {
        Http2FrameStream s = stream(3);
        StreamContext ctx = new StreamContext(s, "corr-1");
        handler.register(ctx);

        DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").add("x-trace", "abc"), false);
        frame.stream(s);
        dispatch(frame);

        assertThat(ctx.getFuture().isDone()).isFalse();
        assertThat(handler.activeStreams()).isEqualTo(1);
    }

    @Test
    void headersWithEndStreamCompletesFuture() throws Exception {
        Http2FrameStream s = stream(5);
        StreamContext ctx = new StreamContext(s, "corr-2");
        handler.register(ctx);

        DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("204"), true);
        frame.stream(s);
        dispatch(frame);

        Http2Response resp = ctx.getFuture().get(1, TimeUnit.SECONDS);
        assertThat(resp.getStatus()).isEqualTo(204);
        assertThat(resp.getCorrelationId()).isEqualTo("corr-2");
        assertThat(handler.activeStreams()).isZero();
    }

    @Test
    void dataFrameAccumulatesAndCompletesOnEndStream() throws Exception {
        Http2FrameStream s = stream(7);
        StreamContext ctx = new StreamContext(s, "corr-3");
        handler.register(ctx);

        DefaultHttp2HeadersFrame h = new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200"), false);
        h.stream(s);
        dispatch(h);

        ByteBuf chunk1 = Unpooled.copiedBuffer("hello-", StandardCharsets.UTF_8);
        DefaultHttp2DataFrame d1 = new DefaultHttp2DataFrame(chunk1, false);
        d1.stream(s);
        dispatch(d1);

        ByteBuf chunk2 = Unpooled.copiedBuffer("world", StandardCharsets.UTF_8);
        DefaultHttp2DataFrame d2 = new DefaultHttp2DataFrame(chunk2, true);
        d2.stream(s);
        dispatch(d2);

        Http2Response resp = ctx.getFuture().get(1, TimeUnit.SECONDS);
        assertThat(new String(resp.getBody(), StandardCharsets.UTF_8)).isEqualTo("hello-world");
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(handler.activeStreams()).isZero();
    }

    @Test
    void headersForUnknownStreamAreIgnored() throws Exception {
        DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200"), true);
        frame.stream(stream(99));
        dispatch(frame);
        assertThat(handler.activeStreams()).isZero();
    }

    @Test
    void dataForUnknownStreamIsReleasedAndIgnored() throws Exception {
        ByteBuf buf = Unpooled.copiedBuffer("orphan", StandardCharsets.UTF_8);
        DefaultHttp2DataFrame frame = new DefaultHttp2DataFrame(buf, true);
        frame.stream(stream(101));
        dispatch(frame);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void resetFrameCompletesFutureExceptionally() throws Exception {
        Http2FrameStream s = stream(9);
        StreamContext ctx = new StreamContext(s, "corr-4");
        handler.register(ctx);

        DefaultHttp2ResetFrame frame = new DefaultHttp2ResetFrame(Http2Error.PROTOCOL_ERROR);
        frame.stream(s);
        dispatch(frame);

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> ctx.getFuture().get(1, TimeUnit.SECONDS));
        assertThat(ex.getCause()).isInstanceOf(Http2TransportException.class);
        assertThat(((Http2TransportException) ex.getCause()).getDetails())
                .contains("RST_STREAM", "streamId=9");
        assertThat(handler.activeStreams()).isZero();
    }

    @Test
    void resetForUnknownStreamIsNoop() throws Exception {
        DefaultHttp2ResetFrame frame = new DefaultHttp2ResetFrame(Http2Error.CANCEL);
        frame.stream(stream(123));
        dispatch(frame);
        assertThat(handler.activeStreams()).isZero();
    }

    @Test
    void exceptionCaughtFailsAllStreamsAndClosesChannel() {
        StreamContext a = new StreamContext(stream(11), "a");
        StreamContext b = new StreamContext(stream(13), "b");
        handler.register(a);
        handler.register(b);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        handler.exceptionCaught(ctx, new IllegalStateException("boom"));

        assertThat(a.getFuture().isCompletedExceptionally()).isTrue();
        assertThat(b.getFuture().isCompletedExceptionally()).isTrue();
        assertThat(handler.activeStreams()).isZero();
        verify(ctx).close();
    }
}
