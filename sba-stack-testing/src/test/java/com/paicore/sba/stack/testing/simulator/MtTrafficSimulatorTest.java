package com.paicore.sba.stack.testing.simulator;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.flow.MtFlow;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import com.paicore.sba.stack.testing.mock.MtDeliveryBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MtTrafficSimulatorTest {

    private MockSbaCoreServer mock;
    private MtFlow flow;

    @BeforeEach
    void start() {
        mock = MockSbaCoreServer.startWithDefaults();
        flow = new MtFlow(mock.baseUri(),
                mock.issueToken("smsf-app", "smsf.mt"));
    }

    @AfterEach
    void stop() {
        flow.close();
        mock.close();
    }

    @Test
    @DisplayName("runConcurrent happy path: every message is DELIVERED and successRate == 1.0")
    void happyPathAllDelivered() {
        List<MtMessageRequest> requests = IntStream.range(0, 5)
                .mapToObj(i -> TestFixtures.defaultMtRequest("mt-msg-" + i))
                .toList();

        try (MtTrafficSimulator simulator = new MtTrafficSimulator(flow)) {
            MtTrafficSimulator.Result result =
                    simulator.runConcurrent(requests, Duration.ofSeconds(15));

            assertThat(result.getSubmitted()).isEqualTo(requests.size());
            assertThat(result.getDelivered()).isEqualTo(requests.size());
            assertThat(result.getFailed()).isZero();
            assertThat(result.getElapsed()).isNotNull();
            assertThat(result.successRate()).isEqualTo(1.0);
            assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
            assertThat(mock.getMtCount().get()).isEqualTo(requests.size());
        }
    }

    @Test
    @DisplayName("runConcurrent with an empty list short-circuits: successRate=0.0 and throughput=submitted")
    void emptyListReturnsZeroSuccessRate() {
        try (MtTrafficSimulator simulator = new MtTrafficSimulator(flow)) {
            MtTrafficSimulator.Result result =
                    simulator.runConcurrent(List.of(), Duration.ofSeconds(2));

            assertThat(result.getSubmitted()).isZero();
            assertThat(result.getDelivered()).isZero();
            assertThat(result.getFailed()).isZero();
            assertThat(result.successRate()).isEqualTo(0.0);
            // ms==0 branch in throughputPerSecond -> returns submitted (which is 0).
            assertThat(result.throughputPerSecond()).isZero();
        }
    }

    @Test
    @DisplayName("runConcurrent counts failures when the mock receiver always rejects with 503")
    void rejectionsAreCountedAsFailed() {
        // alwaysFail(503, ...) keeps the MtFlow retry engine on UNDELIVERABLE
        // until maxAttempts is hit — every dispatch lands in the failed bucket.
        mock.setMtBehavior(MtDeliveryBehavior.alwaysFail(503, "SMSF-5002", "permanent"));
        List<MtMessageRequest> requests = IntStream.range(0, 3)
                .mapToObj(i -> TestFixtures.defaultMtRequest("mt-fail-" + i))
                .toList();

        try (MtTrafficSimulator simulator = new MtTrafficSimulator(flow)) {
            MtTrafficSimulator.Result result =
                    simulator.runConcurrent(requests, Duration.ofSeconds(30));

            assertThat(result.getSubmitted()).isEqualTo(requests.size());
            assertThat(result.getDelivered()).isZero();
            assertThat(result.getFailed()).isEqualTo(requests.size());
            assertThat(result.successRate()).isEqualTo(0.0);
            assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("runConcurrent with an unrealistic 1ms deadline returns partial stats without throwing")
    void timeoutReturnsPartialResults() {
        List<MtMessageRequest> requests = IntStream.range(0, 50)
                .mapToObj(i -> TestFixtures.defaultMtRequest("mt-to-" + i))
                .toList();

        try (MtTrafficSimulator simulator = new MtTrafficSimulator(flow)) {
            MtTrafficSimulator.Result result =
                    simulator.runConcurrent(requests, Duration.ofMillis(1));

            assertThat(result.getSubmitted()).isEqualTo(requests.size());
            assertThat(result.getDelivered() + result.getFailed())
                    .isBetween(0, requests.size());
        }
    }

    @Test
    @DisplayName("Result.throughputPerSecond returns submitted when elapsed is exactly 0 ms")
    void throughputFallsBackToSubmittedWhenElapsedZero() {
        MtTrafficSimulator.Result zeroElapsed =
                new MtTrafficSimulator.Result(9, 9, 0, Duration.ZERO);

        assertThat(zeroElapsed.throughputPerSecond()).isEqualTo(9.0);
        assertThat(zeroElapsed.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Result.successRate returns 0.0 when submitted is 0")
    void successRateIsZeroWhenNothingSubmitted() {
        MtTrafficSimulator.Result empty =
                new MtTrafficSimulator.Result(0, 0, 0, Duration.ofMillis(10));

        assertThat(empty.successRate()).isEqualTo(0.0);
        assertThat(empty.throughputPerSecond()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Result throughputPerSecond uses ms-based math when elapsed > 0")
    void throughputComputesWhenElapsedPositive() {
        MtTrafficSimulator.Result r =
                new MtTrafficSimulator.Result(20, 18, 2, Duration.ofMillis(1000));

        assertThat(r.throughputPerSecond()).isEqualTo(20.0);
        assertThat(r.successRate()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("close() shuts the dispatchPool down so the simulator works with try-with-resources")
    void closeShutsDispatchPool() {
        MtTrafficSimulator simulator = new MtTrafficSimulator(flow);
        simulator.close();
        assertThatCode(simulator::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("runConcurrent surfaces InterruptedException when the caller thread is interrupted")
    void interruptedExceptionBranchIsHandled() throws Exception {
        // alwaysFail with a delay would block too long; use the default deliver
        // path but interrupt the test thread mid-wait to exercise the catch
        // (InterruptedException) branch in MtTrafficSimulator.
        List<MtMessageRequest> requests = IntStream.range(0, 30)
                .mapToObj(i -> TestFixtures.defaultMtRequest("mt-int-" + i))
                .toList();

        try (MtTrafficSimulator simulator = new MtTrafficSimulator(flow)) {
            Thread runner = Thread.currentThread();
            ScheduledExecutorService interrupter = Executors.newSingleThreadScheduledExecutor();
            interrupter.schedule(runner::interrupt, 5, TimeUnit.MILLISECONDS);

            MtTrafficSimulator.Result result =
                    simulator.runConcurrent(requests, Duration.ofSeconds(30));

            assertThat(Thread.interrupted()).isTrue();
            interrupter.shutdown();
            assertThat(interrupter.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.getSubmitted()).isEqualTo(requests.size());
            assertThat(result.getDelivered() + result.getFailed())
                    .isBetween(0, requests.size());
        }
    }
}
