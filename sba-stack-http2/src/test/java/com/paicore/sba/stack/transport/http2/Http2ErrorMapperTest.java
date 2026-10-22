package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.transport.http2.error.Http2ErrorMapper;
import com.paicore.sba.stack.utils.ErrorCodes;
import io.netty.handler.codec.http2.Http2Error;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2ErrorMapperTest {

    @Test
    void rstStreamIncludesKnownErrorName() {
        Http2TransportException ex = Http2ErrorMapper.fromRstStream(7, Http2Error.PROTOCOL_ERROR.code());
        assertEquals(ErrorCodes.HTTP2_TRANSPORT_FAILURE, ex.getCode());
        assertTrue(ex.getDetails().contains("streamId=7"));
        assertTrue(ex.getDetails().contains("PROTOCOL_ERROR"));
    }

    @Test
    void rstStreamHandlesUnknownErrorCode() {
        Http2TransportException ex = Http2ErrorMapper.fromRstStream(11, 0xFFFFL);
        assertTrue(ex.getDetails().contains("UNKNOWN"));
    }

    @Test
    void goAwayIncludesDebugData() {
        Http2TransportException ex = Http2ErrorMapper.fromGoAway(101,
                Http2Error.ENHANCE_YOUR_CALM.code(), "rate-limited");
        assertTrue(ex.getDetails().contains("lastStreamId=101"));
        assertTrue(ex.getDetails().contains("rate-limited"));
    }

    @Test
    void wrapPropagatesCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        Http2TransportException ex = Http2ErrorMapper.wrap("pipeline", cause);
        assertEquals(cause, ex.getCause());
    }

    @Test
    void goAwayOmitsDebugSectionWhenNullOrEmpty() {
        Http2TransportException nullDebug = Http2ErrorMapper.fromGoAway(0,
                Http2Error.NO_ERROR.code(), null);
        Http2TransportException emptyDebug = Http2ErrorMapper.fromGoAway(0,
                Http2Error.NO_ERROR.code(), "");
        assertTrue(!nullDebug.getDetails().contains("debug="));
        assertTrue(!emptyDebug.getDetails().contains("debug="));
    }

    @Test
    void goAwayHandlesUnknownErrorCode() {
        Http2TransportException ex = Http2ErrorMapper.fromGoAway(0, 0xFFFFL, "x");
        assertTrue(ex.getDetails().contains("UNKNOWN"));
    }

    @Test
    void privateConstructorRefusesInstantiation() throws Exception {
        java.lang.reflect.Constructor<Http2ErrorMapper> ctor =
                Http2ErrorMapper.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        try {
            ctor.newInstance();
            org.junit.jupiter.api.Assertions.fail("expected InvocationTargetException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
    }
}
