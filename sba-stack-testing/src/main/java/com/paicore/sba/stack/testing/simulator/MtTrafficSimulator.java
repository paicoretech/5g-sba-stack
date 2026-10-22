package com.paicore.sba.stack.testing.simulator;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import com.paicore.sba.stack.testing.flow.MtFlow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires a list of {@link MtMessageRequest}s through an {@link MtFlow} using a
 * virtual-thread pool so we can stress HTTP/2 concurrency without spawning
 * an OS thread per request — the model CLAUDE-Testing.md §"MT Simulation
 * Queue" prescribes.
 *
 * <p>Returns aggregated statistics so tests can assert success rate, total
 * latency, and that the underlying HTTP/2 connection multiplexed every
 * stream onto a single TCP connection.
 */
@Slf4j
public class MtTrafficSimulator implements AutoCloseable {

    private final MtFlow flow;
    private final ExecutorService dispatchPool;

    public MtTrafficSimulator(MtFlow flow) {
        this.flow = flow;
        this.dispatchPool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mt-sim-", 0).factory());
    }

    public Result runConcurrent(List<MtMessageRequest> messages, Duration deadline) {
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();
        Instant start = Instant.now();

        List<CompletableFuture<MtResponse>> futures = messages.stream()
                .map(msg -> {
                    pending.incrementAndGet();
                    return CompletableFuture.supplyAsync(() -> flow.deliver(msg), dispatchPool)
                            .whenComplete((resp, err) -> {
                                if (err != null || resp == null || resp.getStatus() != DeliveryStatus.DELIVERED) {
                                    failed.incrementAndGet();
                                } else {
                                    delivered.incrementAndGet();
                                }
                            });
                })
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MT simulator interrupted before {}: {}", deadline, e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.warn("MT simulator did not finish within {}: {}", deadline, e.getMessage());
        }
        Duration elapsed = Duration.between(start, Instant.now());
        return new Result(messages.size(), delivered.get(), failed.get(), elapsed);
    }

    @Override
    public void close() {
        dispatchPool.shutdown();
    }

    @Getter
    public static final class Result {
        private final int submitted;
        private final int delivered;
        private final int failed;
        private final Duration elapsed;

        public Result(int submitted, int delivered, int failed, Duration elapsed) {
            this.submitted = submitted;
            this.delivered = delivered;
            this.failed = failed;
            this.elapsed = elapsed;
        }

        public double throughputPerSecond() {
            long ms = elapsed.toMillis();
            return ms == 0 ? submitted : submitted * 1000.0 / ms;
        }

        public double successRate() {
            return submitted == 0 ? 0.0 : (double) delivered / submitted;
        }
    }
}
