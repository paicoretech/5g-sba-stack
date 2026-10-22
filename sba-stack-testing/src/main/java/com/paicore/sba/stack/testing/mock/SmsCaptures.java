package com.paicore.sba.stack.testing.mock;

import com.paicore.sba.stack.openapi.model.SmsRecordData;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;

import java.util.Arrays;
import java.util.Objects;

/**
 * Shared equality / hashCode / toString helpers for the {@link
 * MockSbaCoreServer.MoCapture} and {@link MockSbaCoreServer.MtCapture}
 * records. They have the same four components (supi, smsRecord, tpdu bytes,
 * decoded TPDU) so they need the same byte-array-aware equality logic — this
 * helper lets both records inherit it without re-declaring it twice.
 */
final class SmsCaptures {

    private SmsCaptures() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Returns {@code true} when {@code other} is the same exact record type as
     * {@code self} and the four component values are equal (using
     * {@link Arrays#equals(byte[], byte[])} for the TPDU byte array).
     */
    @SuppressWarnings("unchecked")
    static <T extends Record> boolean equalSnapshots(T self, Object other, Class<T> type) {
        if (self == other) return true;
        if (!type.isInstance(other)) return false;
        return componentsEqual(self, (T) other);
    }

    private static <T extends Record> boolean componentsEqual(T self, T other) {
        Object[] a = unpack(self);
        Object[] b = unpack(other);
        return Objects.equals(a[0], b[0])
                && Objects.equals(a[1], b[1])
                && Objects.equals(a[3], b[3])
                && Arrays.equals((byte[]) a[2], (byte[]) b[2]);
    }

    private static Object[] unpack(Record r) {
        try {
            var comps = r.getClass().getRecordComponents();
            Object[] out = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                out[i] = comps[i].getAccessor().invoke(r);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Record introspection failed", e);
        }
    }

    static int hash(String supi, SmsRecordData smsRecord, SmsTpdu decoded, byte[] tpdu) {
        return 31 * Objects.hash(supi, smsRecord, decoded) + Arrays.hashCode(tpdu);
    }

    static String render(String label, String supi, SmsRecordData smsRecord,
                         SmsTpdu decoded, byte[] tpdu) {
        return label + "[supi=" + supi
                + ", smsRecord=" + smsRecord
                + ", decoded=" + decoded
                + ", tpdu=" + Arrays.toString(tpdu) + "]";
    }
}
