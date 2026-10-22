package com.paicore.sba.stack.testing.util;

import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TpduInspectorTest {

    @Test
    @DisplayName("null/empty payload is reported as (empty TPDU)")
    void emptyInputReturnsSentinel() {
        assertThat(TpduInspector.inspect(null)).isEqualTo("(empty TPDU)");
        assertThat(TpduInspector.inspect(new byte[0])).isEqualTo("(empty TPDU)");
    }

    @Test
    @DisplayName("garbage bytes report a friendly jss7 decode failure")
    void garbageBytesReportDecodeFailure() {
        byte[] garbage = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

        String summary = TpduInspector.inspect(garbage);

        assertThat(summary).startsWith("(jss7 decode failed:");
    }

    @Test
    @DisplayName("UCS-2 SMS_SUBMIT renders destination, dcs=0x08, udl and decoded text")
    void ucs2SubmitDescriptionContainsAllFields() {
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "Hi");

        String summary = TpduInspector.inspect(tpdu);

        assertThat(summary)
                .startsWith("SMS_SUBMIT raw=")
                .contains("dest=+50212345678")
                .contains("dcs=0x08")
                .contains("text='Hi'");
    }

    @Test
    @DisplayName("GSM-7 SMS_SUBMIT advertises dcs=0x00 and the packed text")
    void gsm7SubmitDescriptionShowsDcs0x00() {
        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "Hello");

        String summary = TpduInspector.inspect(tpdu);

        assertThat(summary)
                .contains("dcs=0x00")
                .contains("text='Hello'");
    }

    @Test
    @DisplayName("a network SMS_DELIVER byte stream surfaces as SMS_DELIVER_REPORT")
    void networkDeliverIsRenderedAsDeliverReport() {
        byte[] tpdu = TpduFixture.gsm7Deliver("+8613915900000", "DR");

        String summary = TpduInspector.inspect(tpdu);

        assertThat(summary)
                .startsWith("SMS_DELIVER_REPORT raw=")
                .contains("mobile-originated=true");
    }

    @Test
    @DisplayName("newlines inside the decoded text are escaped as \\n")
    void newlinesAreEscaped() {
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "a\nb");

        String summary = TpduInspector.inspect(tpdu);

        assertThat(summary).contains("text='a\\nb'");
    }

    @Test
    @DisplayName("constructor is private and throws to enforce utility-class semantics")
    void utilityConstructorRefusesInstantiation() throws Exception {
        Constructor<TpduInspector> ctor = TpduInspector.class.getDeclaredConstructor();
        ctor.setAccessible(true);

        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
