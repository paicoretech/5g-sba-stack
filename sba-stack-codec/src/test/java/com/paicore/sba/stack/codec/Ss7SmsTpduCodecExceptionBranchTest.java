package com.paicore.sba.stack.codec;

import com.paicore.sba.stack.exceptions.InvalidTpduException;
import com.paicore.sba.stack.exceptions.TpduDecodeException;
import com.paicore.sba.stack.exceptions.TpduEncodeException;
import com.paicore.sba.stack.exceptions.UnsupportedDcsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.smstpdu.SmsSubmitTpduImpl;
import org.restcomm.protocols.ss7.map.smstpdu.SmsTpduImpl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Exercises the defensive catch-branches inside {@link Ss7SmsTpduCodec#decode}
 * and {@link Ss7SmsTpduCodec#encode}. These cannot be reached with realistic
 * inputs (the jss7 codepaths declare {@code MAPException}), so we stub the jss7
 * factory call so it raises each exception family in turn.
 */
class Ss7SmsTpduCodecExceptionBranchTest {

    private final Ss7SmsTpduCodec codec = new Ss7SmsTpduCodec();
    private static final byte[] NON_EMPTY = new byte[]{0x01};

    @Test
    @DisplayName("decode re-throws an SDK-typed exception untouched")
    void decodeRethrowsSdkTypedException() {
        TpduDecodeException original = new TpduDecodeException("typed");
        try (MockedStatic<SmsTpduImpl> stub = mockStatic(SmsTpduImpl.class)) {
            stub.when(() -> SmsTpduImpl.createInstance(any(byte[].class), eq(true), any()))
                    .thenThrow(original);
            TpduDecodeException thrown = assertThrows(TpduDecodeException.class, () -> codec.decode(NON_EMPTY));
            assertSame(original, thrown);
        }
    }

    @Test
    @DisplayName("decode re-throws UnsupportedDcsException as-is")
    void decodeRethrowsUnsupportedDcs() {
        UnsupportedDcsException original = new UnsupportedDcsException(0xFF);
        try (MockedStatic<SmsTpduImpl> stub = mockStatic(SmsTpduImpl.class)) {
            stub.when(() -> SmsTpduImpl.createInstance(any(byte[].class), eq(true), any()))
                    .thenThrow(original);
            UnsupportedDcsException thrown = assertThrows(UnsupportedDcsException.class,
                    () -> codec.decode(NON_EMPTY));
            assertSame(original, thrown);
        }
    }

    @Test
    @DisplayName("decode re-throws InvalidTpduException as-is")
    void decodeRethrowsInvalidTpdu() {
        InvalidTpduException original = new InvalidTpduException("invalid");
        try (MockedStatic<SmsTpduImpl> stub = mockStatic(SmsTpduImpl.class)) {
            stub.when(() -> SmsTpduImpl.createInstance(any(byte[].class), eq(true), any()))
                    .thenThrow(original);
            InvalidTpduException thrown = assertThrows(InvalidTpduException.class, () -> codec.decode(NON_EMPTY));
            assertSame(original, thrown);
        }
    }

    @Test
    @DisplayName("decode wraps a generic RuntimeException as TpduDecodeException")
    void decodeWrapsRuntimeException() {
        RuntimeException boom = new RuntimeException("unexpected");
        try (MockedStatic<SmsTpduImpl> stub = mockStatic(SmsTpduImpl.class)) {
            stub.when(() -> SmsTpduImpl.createInstance(any(byte[].class), eq(true), any()))
                    .thenThrow(boom);
            TpduDecodeException thrown = assertThrows(TpduDecodeException.class, () -> codec.decode(NON_EMPTY));
            assertSame(boom, thrown.getCause());
        }
    }

    @Test
    @DisplayName("encode wraps MAPException as TpduEncodeException")
    void encodeWrapsMapException() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("123")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("x")
                .dataCoding(0x00)
                .build();
        MAPException mapEx = new MAPException("jss7 boom");
        try (MockedConstruction<SmsSubmitTpduImpl> ignored =
                     mockConstruction(SmsSubmitTpduImpl.class,
                             (mock, ctx) -> when(mock.encodeData()).thenThrow(mapEx))) {
            TpduEncodeException thrown = assertThrows(TpduEncodeException.class, () -> codec.encode(req));
            assertSame(mapEx, thrown.getCause());
        }
    }

    @Test
    @DisplayName("encode re-throws TpduEncodeException untouched")
    void encodeRethrowsEncodeException() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("123")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("x")
                .dataCoding(0x00)
                .build();
        TpduEncodeException original = new TpduEncodeException("explicit");
        try (MockedConstruction<SmsSubmitTpduImpl> ignored =
                     mockConstruction(SmsSubmitTpduImpl.class,
                             (mock, ctx) -> when(mock.encodeData()).thenThrow(original))) {
            TpduEncodeException thrown = assertThrows(TpduEncodeException.class, () -> codec.encode(req));
            assertSame(original, thrown);
        }
    }

    @Test
    @DisplayName("encode re-throws UnsupportedDcsException untouched")
    void encodeRethrowsUnsupportedDcs() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("123")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("x")
                .dataCoding(0x00)
                .build();
        UnsupportedDcsException original = new UnsupportedDcsException(0xFF);
        try (MockedConstruction<SmsSubmitTpduImpl> ignored =
                     mockConstruction(SmsSubmitTpduImpl.class,
                             (mock, ctx) -> when(mock.encodeData()).thenThrow(original))) {
            UnsupportedDcsException thrown = assertThrows(UnsupportedDcsException.class, () -> codec.encode(req));
            assertSame(original, thrown);
        }
    }

    @Test
    @DisplayName("encode wraps a generic RuntimeException as TpduEncodeException")
    void encodeWrapsRuntimeException() {
        EncodeTpduRequest req = EncodeTpduRequest.builder()
                .messageType(MessageType.SMS_SUBMIT)
                .destinationAddress("123")
                .destinationAddressTon(1)
                .destinationAddressNpi(1)
                .message("x")
                .dataCoding(0x00)
                .build();
        RuntimeException boom = new RuntimeException("unexpected");
        try (MockedConstruction<SmsSubmitTpduImpl> ignored =
                     mockConstruction(SmsSubmitTpduImpl.class,
                             (mock, ctx) -> when(mock.encodeData()).thenThrow(boom))) {
            TpduEncodeException thrown = assertThrows(TpduEncodeException.class, () -> codec.encode(req));
            assertSame(boom, thrown.getCause());
        }
    }
}
