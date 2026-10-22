package com.paicore.sba.stack.testing.util;

/**
 * Tiny hex+ASCII pretty-printer (16 bytes per row) so the runner can dump the
 * exact multipart body it is about to send. Mirrors the layout Wireshark uses
 * in its packet bytes pane so an operator can correlate the on-wire capture
 * with the in-process trace byte-for-byte.
 */
public final class HexDump {

    private static final int BYTES_PER_ROW = 16;

    private HexDump() {
        throw new IllegalStateException("Utility class");
    }

    public static String format(byte[] data) {
        if (data == null || data.length == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < data.length; offset += BYTES_PER_ROW) {
            int rowLen = Math.min(BYTES_PER_ROW, data.length - offset);
            appendRow(sb, data, offset, rowLen);
            if (offset + BYTES_PER_ROW < data.length) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, byte[] data, int offset, int rowLen) {
        sb.append(String.format("%08x  ", offset));
        for (int i = 0; i < BYTES_PER_ROW; i++) {
            sb.append(i < rowLen ? String.format("%02x ", data[offset + i] & 0xff) : "   ");
            if (i == 7) {
                sb.append(' ');
            }
        }
        sb.append(" |");
        for (int i = 0; i < rowLen; i++) {
            int b = data[offset + i] & 0xff;
            sb.append((b >= 0x20 && b < 0x7f) ? (char) b : '.');
        }
        sb.append('|');
    }
}
