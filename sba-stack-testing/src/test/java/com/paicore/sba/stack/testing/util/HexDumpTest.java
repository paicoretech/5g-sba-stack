package com.paicore.sba.stack.testing.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HexDumpTest {

    @Test
    @DisplayName("null and empty arrays return the (empty) sentinel")
    void emptyInputProducesSentinel() {
        assertThat(HexDump.format(null)).isEqualTo("(empty)");
        assertThat(HexDump.format(new byte[0])).isEqualTo("(empty)");
    }

    @Test
    @DisplayName("a 16-byte row dumps offset, hex bytes and ASCII gutter")
    void singleRowHasOffsetHexAndAscii() {
        byte[] data = "ABCDEFGHIJKLMNOP".getBytes(StandardCharsets.US_ASCII);

        String dump = HexDump.format(data);

        assertThat(dump)
                .doesNotContain("\n")
                .startsWith("00000000  ")
                .contains("41 42 43 44 45 46 47 48  49 4a 4b 4c 4d 4e 4f 50")
                .endsWith("|ABCDEFGHIJKLMNOP|");
    }

    @Test
    @DisplayName("multi-row dumps separate rows with newlines and pad the final row")
    void multipleRowsSplitAtBoundary() {
        byte[] data = new byte[20];
        for (int i = 0; i < 16; i++) data[i] = (byte) i;
        // last 4 bytes are non-printable + one printable so we exercise the '.' fallback
        data[16] = 0x1f;
        data[17] = (byte) 'X';
        data[18] = 0x7f;
        data[19] = (byte) 'Y';

        String[] rows = HexDump.format(data).split("\n");

        assertThat(rows).hasSize(2);
        assertThat(rows[0]).startsWith("00000000  ");
        assertThat(rows[1])
                .startsWith("00000010  ")
                .contains("1f 58 7f 59                                       ")
                .endsWith("|.X.Y|");
    }

    @Test
    @DisplayName("constructor is private and throws to enforce utility-class semantics")
    void utilityConstructorRefusesInstantiation() throws Exception {
        Constructor<HexDump> ctor = HexDump.class.getDeclaredConstructor();
        ctor.setAccessible(true);

        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
