package com.paicore.sba.stack.transport.http2.error;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import io.netty.handler.codec.http2.Http2Error;

public final class Http2ErrorMapper {

    private Http2ErrorMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static Http2TransportException fromRstStream(int streamId, long errorCode) {
        Http2Error error = lookup(errorCode);
        return new Http2TransportException(
                "RST_STREAM streamId=" + streamId + " errorCode=" + errorCode
                        + " (" + (error != null ? error.name() : "UNKNOWN") + ")");
    }

    public static Http2TransportException fromGoAway(int lastStreamId, long errorCode, String debugData) {
        Http2Error error = lookup(errorCode);
        return new Http2TransportException(
                "GOAWAY lastStreamId=" + lastStreamId + " errorCode=" + errorCode
                        + " (" + (error != null ? error.name() : "UNKNOWN") + ")"
                        + (debugData != null && !debugData.isEmpty() ? " debug=" + debugData : ""));
    }

    public static Http2TransportException wrap(String context, Throwable cause) {
        return new Http2TransportException(context, cause);
    }

    private static Http2Error lookup(long code) {
        for (Http2Error e : Http2Error.values()) {
            if (e.code() == code) {
                return e;
            }
        }
        return null;
    }
}
