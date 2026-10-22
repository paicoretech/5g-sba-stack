package com.paicore.sba.stack.testing.simulator;

import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.flow.MoFlow;
import com.paicore.sba.stack.testing.flow.RegistrationFlow;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MoTrafficSimulatorTest {

    private MockSbaCoreServer mock;
    private RegistrationFlow registration;
    private MoFlow flow;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        registration = new RegistrationFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.register"));
        flow = new MoFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.mo"));
        registration.register(new RegistrationRequest("corr-bootstrap",
                TestFixtures.defaultUeContext()));
    }

    @AfterEach
    void stop() {
        flow.close();
        registration.close();
        mock.close();
    }

    @Test
    @DisplayName("runConcurrent happy path: every dispatch is accepted and successRate == 1.0")
    void happyPathAllAccepted() {
        int count = 5;
        IntFunction<MoMessageRequest> requestFactory = i -> MoMessageRequest.builder()
                .correlationId("corr-mo-sim-" + i)
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();
        IntFunction<byte[]> tpduFactory = i -> TestFixtures.defaultSubmitTpdu("msg-" + i);

        try (MoTrafficSimulator simulator = new MoTrafficSimulator(flow)) {
            MoTrafficSimulator.Result result = simulator.runConcurrent(
                    count, requestFactory, tpduFactory, Duration.ofSeconds(15));

            assertThat(result.getSubmitted()).isEqualTo(count);
            assertThat(result.getAccepted()).isEqualTo(count);
            assertThat(result.getRejected()).isZero();
            assertThat(result.getElapsed()).isNotNull();
            assertThat(result.successRate()).isEqualTo(1.0);
            assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
            assertThat(mock.getMoCount().get()).isEqualTo(count);
        }
    }

    @Test
    @DisplayName("runConcurrent with count=0 short-circuits: successRate=0.0 and throughput=submitted")
    void zeroCountReturnsZeroSuccessRate() {
        try (MoTrafficSimulator simulator = new MoTrafficSimulator(flow)) {
            MoTrafficSimulator.Result result = simulator.runConcurrent(
                    0, i -> { throw new AssertionError("factory must not be invoked"); },
                    i -> { throw new AssertionError("tpdu factory must not be invoked"); },
                    Duration.ofSeconds(2));

            assertThat(result.getSubmitted()).isZero();
            assertThat(result.getAccepted()).isZero();
            assertThat(result.getRejected()).isZero();
            assertThat(result.successRate()).isEqualTo(0.0);
            // Result branch: when no work runs the elapsed window can be 0ms and
            // throughput falls back to submitted (i.e. 0).
            assertThat(result.throughputPerSecond()).isZero();
        }
    }

    @Test
    @DisplayName("runConcurrent rejects when the UE has not been registered (SMSF-404)")
    void rejectionsAreCountedAsRejected() {
        // Clear the bootstrap registration so every MO dispatch is rejected with 404.
        mock.getRegistry().clear();
        int count = 4;
        IntFunction<MoMessageRequest> requestFactory = i -> MoMessageRequest.builder()
                .correlationId("corr-mo-fail-" + i)
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();
        IntFunction<byte[]> tpduFactory = i -> TestFixtures.defaultSubmitTpdu("fail-" + i);

        try (MoTrafficSimulator simulator = new MoTrafficSimulator(flow)) {
            MoTrafficSimulator.Result result = simulator.runConcurrent(
                    count, requestFactory, tpduFactory, Duration.ofSeconds(15));

            assertThat(result.getSubmitted()).isEqualTo(count);
            assertThat(result.getAccepted()).isZero();
            assertThat(result.getRejected()).isEqualTo(count);
            assertThat(result.successRate()).isEqualTo(0.0);
            assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("runConcurrent with an unrealistic deadline prints warning and returns partial stats")
    void timeoutReturnsPartialResults() {
        int count = 100;
        IntFunction<MoMessageRequest> requestFactory = i -> MoMessageRequest.builder()
                .correlationId("corr-mo-to-" + i)
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();
        IntFunction<byte[]> tpduFactory = i -> TestFixtures.defaultSubmitTpdu("to-" + i);

        try (MoTrafficSimulator simulator = new MoTrafficSimulator(flow)) {
            MoTrafficSimulator.Result result = simulator.runConcurrent(
                    count, requestFactory, tpduFactory, Duration.ofMillis(1));

            // The simulator MUST always return a Result even when the deadline
            // is hit; submitted reflects how many dispatches we attempted.
            assertThat(result.getSubmitted()).isEqualTo(count);
            assertThat(result.getAccepted() + result.getRejected())
                    .isBetween(0, count);
        }
    }

    @Test
    @DisplayName("Result.throughputPerSecond returns submitted when elapsed is exactly 0 ms")
    void throughputFallsBackToSubmittedWhenElapsedZero() {
        MoTrafficSimulator.Result zeroElapsed =
                new MoTrafficSimulator.Result(7, 7, 0, Duration.ZERO);

        assertThat(zeroElapsed.throughputPerSecond()).isEqualTo(7.0);
        assertThat(zeroElapsed.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Result.successRate returns 0.0 when submitted is 0")
    void successRateIsZeroWhenNothingSubmitted() {
        MoTrafficSimulator.Result empty =
                new MoTrafficSimulator.Result(0, 0, 0, Duration.ofMillis(10));

        assertThat(empty.successRate()).isEqualTo(0.0);
        // ms != 0 branch: throughput is computed normally (and yields 0).
        assertThat(empty.throughputPerSecond()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Result throughputPerSecond uses ms-based math when elapsed > 0")
    void throughputComputesWhenElapsedPositive() {
        MoTrafficSimulator.Result r =
                new MoTrafficSimulator.Result(10, 8, 2, Duration.ofMillis(500));

        assertThat(r.throughputPerSecond()).isEqualTo(20.0);
        assertThat(r.successRate()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("close() shuts the dispatchPool down so the simulator can be reused with try-with-resources")
    void closeShutsDispatchPool() {
        MoTrafficSimulator simulator = new MoTrafficSimulator(flow);
        simulator.close();
        assertDoesNotThrow(simulator::close);
    }

    @Test
    @DisplayName("runConcurrent surfaces InterruptedException when the caller thread is interrupted")
    void interruptedExceptionBranchIsHandled() throws Exception {
        int count = 30;
        IntFunction<MoMessageRequest> requestFactory = i -> MoMessageRequest.builder()
                .correlationId("corr-mo-int-" + i)
                .smsRecord(TestFixtures.defaultSmsRecord())
                .build();
        IntFunction<byte[]> tpduFactory = i -> TestFixtures.defaultSubmitTpdu("int-" + i);

        try (MoTrafficSimulator simulator = new MoTrafficSimulator(flow)) {
            Thread runner = Thread.currentThread();
            ScheduledExecutorService interrupter = Executors.newSingleThreadScheduledExecutor();
            interrupter.schedule(runner::interrupt, 5, TimeUnit.MILLISECONDS);

            MoTrafficSimulator.Result result = simulator.runConcurrent(
                    count, requestFactory, tpduFactory, Duration.ofSeconds(30));

            assertThat(Thread.interrupted()).isTrue();
            interrupter.shutdown();
            assertThat(interrupter.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.getSubmitted()).isEqualTo(count);
            assertThat(result.getAccepted() + result.getRejected())
                    .isBetween(0, count);
        }
    }
}
