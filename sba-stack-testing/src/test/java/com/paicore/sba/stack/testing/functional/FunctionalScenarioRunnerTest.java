package com.paicore.sba.stack.testing.functional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link FunctionalScenarioRunner#main(String[])} end-to-end so the
 * runner's whole orchestration sequence (register, MO UCS-2, MO GSM-7,
 * MT UCS-2, MT GSM-7, deregister, traffic summary, curl-hint dump) executes
 * in-process against an ephemeral {@code MockSbaH2cServer}. The mock is bound
 * to {@code 127.0.0.1:0} and the idle window is set to {@code 0} so the JVM
 * never blocks on the operator-friendly Wireshark capture pause.
 */
class FunctionalScenarioRunnerTest {

    @Test
    @DisplayName("main() runs the full six-step scenario without errors")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mainRunsFullScenario() {
        assertThatCode(() -> FunctionalScenarioRunner.main(new String[]{"127.0.0.1", "0", "0"}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("private constructor is forbidden")
    void constructorIsForbidden() throws Exception {
        Constructor<FunctionalScenarioRunner> ctor =
                FunctionalScenarioRunner.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessage("Main class");
    }

    @Test
    @DisplayName("class is final and has the documented utility shape")
    void classShape() {
        assertThat(java.lang.reflect.Modifier.isFinal(FunctionalScenarioRunner.class.getModifiers()))
                .as("FunctionalScenarioRunner must be final")
                .isTrue();
        assertThat(FunctionalScenarioRunner.class.getDeclaredConstructors())
                .as("exactly one (private) constructor")
                .hasSize(1);
    }
}
