package com.paicore.sba.stack.testing.simulator;

import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import com.paicore.sba.stack.testing.flow.MoFlow;
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
import java.util.function.IntFunction;

/**
 * Fires a batch of MO sendsms requests concurrently through an {@link MoFlow}
 * on a virtual-thread pool. Mirrors {@link MtTrafficSimulator} so the runner
 * generates symmetric MO+MT load and the wire trace shows both flows.
 */
@Slf4j
public class MoTrafficSimulator implements AutoCloseable {

    private final MoFlow flow;
    private final ExecutorService dispatchPool;

    public MoTrafficSimulator(MoFlow flow) {
        this.flow = flow;
        this.dispatchPool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mo-sim-", 0).factory());
    }

    public Result runConcurrent(int count, IntFunction<MoMessageRequest> requestFactory,
                                IntFunction<byte[]> tpduFactory, Duration deadline) {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Instant start = Instant.now();

        List<CompletableFuture<MoResponse>> futures = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                                () -> flow.sendMo(requestFactory.apply(i), tpduFactory.apply(i)),
                                dispatchPool)
                        .whenComplete((resp, err) -> {
                            if (err == null && resp != null && resp.isAccepted()) {
                                accepted.incrementAndGet();
                            } else {
                                rejected.incrementAndGet();
                            }
                        }))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MO simulator interrupted before {}: {}", deadline, e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.warn("MO simulator did not finish within {}: {}", deadline, e.getMessage());
        }
        return new Result(count, accepted.get(), rejected.get(), Duration.between(start, Instant.now()));
    }

    @Override
    public void close() {
        dispatchPool.shutdown();
    }

    @Getter
    public static final class Result {
        private final int submitted;
        private final int accepted;
        private final int rejected;
        private final Duration elapsed;

        public Result(int submitted, int accepted, int rejected, Duration elapsed) {
            this.submitted = submitted;
            this.accepted = accepted;
            this.rejected = rejected;
            this.elapsed = elapsed;
        }

        public double throughputPerSecond() {
            long ms = elapsed.toMillis();
            return ms == 0 ? submitted : submitted * 1000.0 / ms;
        }

        public double successRate() {
            return submitted == 0 ? 0.0 : (double) accepted / submitted;
        }
    }
}
