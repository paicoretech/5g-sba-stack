package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.transport.http2.dto.Http2Response;
import com.paicore.sba.stack.transport.http2.dto.StreamContext;
import com.paicore.sba.stack.transport.http2.error.Http2ErrorMapper;
import com.paicore.sba.stack.transport.http2.util.Http2Constants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class Http2StreamHandler extends SimpleChannelInboundHandler<Http2Frame> {

    private final Map<Http2FrameStream, StreamContext> streams = new ConcurrentHashMap<>();
    private final Map<Http2FrameStream, ByteArrayOutputStream> bodies = new ConcurrentHashMap<>();
    private final Map<Http2FrameStream, Http2Response.Http2ResponseBuilder> partials = new ConcurrentHashMap<>();

    public void register(StreamContext ctx) {
        Http2FrameStream stream = ctx.getStream();
        streams.put(stream, ctx);
        bodies.put(stream, new ByteArrayOutputStream());
        partials.put(stream, Http2Response.builder().correlationId(ctx.getCorrelationId()));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelCtx, Http2Frame frame) {
        if (frame instanceof Http2HeadersFrame headersFrame) {
            onHeaders(headersFrame);
        } else if (frame instanceof Http2DataFrame dataFrame) {
            onData(dataFrame);
        } else if (frame instanceof Http2ResetFrame resetFrame) {
            onReset(resetFrame);
        }
    }

    private void onHeaders(Http2HeadersFrame frame) {
        Http2FrameStream stream = frame.stream();
        Http2Response.Http2ResponseBuilder builder = partials.get(stream);
        if (Objects.isNull(builder)) {
            return;
        }
        Map<String, String> headers = new HashMap<>();
        frame.headers().forEach(e -> headers.put(e.getKey().toString(), e.getValue().toString()));
        builder.headers(headers);
        CharSequence status = frame.headers().get(Http2Constants.PSEUDO_STATUS);
        if (Objects.nonNull(status)) {
            builder.status(Integer.parseInt(status.toString()));
        }
        if (frame.isEndStream()) {
            complete(stream);
        }
    }

    private void onData(Http2DataFrame frame) {
        Http2FrameStream stream = frame.stream();
        ByteArrayOutputStream sink = bodies.get(stream);
        if (Objects.isNull(sink)) {
            return;
        }
        ByteBuf content = frame.content();
        byte[] chunk = new byte[content.readableBytes()];
        content.readBytes(chunk);
        sink.write(chunk, 0, chunk.length);
        if (frame.isEndStream()) {
            complete(stream);
        }
    }

    private void onReset(Http2ResetFrame frame) {
        Http2FrameStream stream = frame.stream();
        StreamContext ctx = streams.remove(stream);
        bodies.remove(stream);
        partials.remove(stream);
        if (Objects.nonNull(ctx)) {
            ctx.getFuture().completeExceptionally(
                    Http2ErrorMapper.fromRstStream(stream.id(), frame.errorCode()));
        }
    }

    private void complete(Http2FrameStream stream) {
        StreamContext ctx = streams.remove(stream);
        ByteArrayOutputStream sink = bodies.remove(stream);
        Http2Response.Http2ResponseBuilder builder = partials.remove(stream);
        if (Objects.isNull(ctx) || Objects.isNull(builder)) {
            return;
        }
        if (Objects.nonNull(sink)) {
            builder.body(sink.toByteArray());
        }
        ctx.getFuture().complete(builder.build());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelCtx, Throwable cause) {
        log.error("HTTP/2 pipeline error: {}", cause.getMessage(), cause);
        streams.forEach((id, ctx) ->
                ctx.getFuture().completeExceptionally(Http2ErrorMapper.wrap("pipeline error", cause)));
        streams.clear();
        bodies.clear();
        partials.clear();
        channelCtx.close();
    }

    int activeStreams() {
        return streams.size();
    }
}
