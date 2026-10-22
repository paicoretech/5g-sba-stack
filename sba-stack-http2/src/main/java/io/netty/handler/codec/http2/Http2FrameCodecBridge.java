package io.netty.handler.codec.http2;

public final class Http2FrameCodecBridge {

    private Http2FrameCodecBridge() {
    }

    public static Http2FrameStream newStream(Http2FrameCodec codec) {
        return codec.newStream();
    }
}
