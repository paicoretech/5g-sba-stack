package com.paicore.sba.stack.security.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwksKeyStoreTest {

    private static final String SAMPLE_JWKS = """
            {"keys":[{"kty":"RSA","kid":"sample","use":"sig","alg":"RS256",
            "n":"sXch2KO_DfsfA1bWPCO3xL6f9j-Y0wgIkW7AfRfvUF7nbJqJ20Mz4WfDsHRy-OWtZcvWB6mScNI4SmoSiVoTBzPa4XKsRwsAfHcuB80odp0ucIkjMNDc-AYL75YIvO4Mc1jXcM9zBcGcoeeftLAlnELLZCNlk_yYAFYRizq05bIBLHcvIBpDXa_xK17v4ulVtVbThnFI3iv3iD55GsX5nlncDFwxv1WIm-PrJjVOJUKNzh5N6FoUx0YOySf-aDS-zJlGUDikutpHcVuRsmlW7CHfgVPCqHb2GNFPLnB7s27UV-LCEbXVKlbQH0Ll-rJUFmaIZyx9CO27wF6Yqo6V6w",
            "e":"AQAB"}]}
            """;

    /** Generous budget: these waits only need to outlast a cold JVM, never a real timeout. */
    private static final long TIMEOUT_SECONDS = 30;

    private AtomicInteger loadCount;
    private JWKSet sample;

    @BeforeEach
    void setUp() throws ParseException {
        loadCount = new AtomicInteger();
        sample = JWKSet.parse(SAMPLE_JWKS);
    }

    @Test
    @DisplayName("first get triggers the loader; subsequent reads hit the cache")
    void cachesAfterFirstLoad() {
        JwksKeyStore store = new JwksKeyStore(countingLoader(() -> sample));

        JWKSet first = store.get();
        JWKSet second = store.get();

        assertThat(first).isSameAs(second);
        assertThat(first.getKeyByKeyId("sample")).isNotNull();
        assertThat(loadCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh forces a new load even when cache is warm")
    void refreshForcesReload() {
        JwksKeyStore store = new JwksKeyStore(countingLoader(() -> sample));
        store.get();

        store.refresh();

        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate clears the cache; next call reloads")
    void invalidateClearsCache() {
        JwksKeyStore store = new JwksKeyStore(countingLoader(() -> sample));
        store.get();

        store.invalidate("default");
        store.get();

        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("loader exceptions surface as AuthenticationException")
    void loaderExceptionSurfaces() {
        JwksKeyStore store = new JwksKeyStore(() -> {
            throw new AuthenticationException("simulated boom");
        });

        assertThatThrownBy(store::get)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("simulated boom"));
    }

    @Test
    @DisplayName("loader returning null is treated as an error")
    void nullLoaderReturnIsError() {
        JwksKeyStore store = new JwksKeyStore(() -> null);

        assertThatThrownBy(store::get)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("null JWKSet"));
    }

    @Test
    @DisplayName("non-AuthenticationException from the loader is wrapped")
    void unexpectedLoaderExceptionWrapped() {
        JwksKeyStore store = new JwksKeyStore(() -> {
            throw new RuntimeException("kaboom");
        });

        assertThatThrownBy(store::get)
                .isInstanceOf(AuthenticationException.class)
                .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                        .contains("Failed to load JWKS"));
    }

    @Test
    @DisplayName("concurrent miss-path callers share a single in-flight load")
    void dedupesConcurrentLoaders() throws InterruptedException {
        // Hand off through latches instead of polling the counter: the loader runs on the common
        // pool, and on a cold JVM that round trip outlasts any short polling budget.
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JwksKeyStore store = new JwksKeyStore(() -> {
            loadCount.incrementAndGet();
            loaderEntered.countDown();
            awaitQuietly(release);
            return sample;
        }, "default", "test", Duration.ofSeconds(30));

        Thread t1 = new Thread(store::get, "jwks-caller-1");
        Thread t2 = new Thread(store::get, "jwks-caller-2");
        Thread t3 = new Thread(store::get, "jwks-caller-3");
        t1.start();
        t2.start();
        t3.start();

        assertThat(loaderEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the loader should have been entered")
                .isTrue();
        // Ensure all callers share the same in-flight load before releasing it.
        awaitAllParked(t1, t2, t3);

        release.countDown();
        t1.join(TIMEOUT_SECONDS * 1_000);
        t2.join(TIMEOUT_SECONDS * 1_000);
        t3.join(TIMEOUT_SECONDS * 1_000);

        assertThat(loadCount.get()).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until every caller is blocked on the shared in-flight future. */
    private static void awaitAllParked(Thread... threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Stream.of(threads).allMatch(JwksKeyStoreTest::isParked)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("callers did not park on the in-flight load: "
                + Stream.of(threads).map(t -> t.getName() + "=" + t.getState()).toList());
    }

    private static boolean isParked(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    @Test
    @DisplayName("constructor rejects null loader")
    void constructorRejectsNullLoader() {
        assertThatThrownBy(() -> new JwksKeyStore(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("loader");
    }

    @Test
    @DisplayName("invalidate with null key is a no-op")
    void invalidateNullKeyIsNoOp() {
        JwksKeyStore store = new JwksKeyStore(countingLoader(() -> sample));

        store.invalidate(null);

        assertThat(loadCount.get()).isZero();
    }

    @Test
    @DisplayName("get times out when loader is slower than awaitTimeout")
    void getRespectsAwaitTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        JwksKeyStore store = new JwksKeyStore(() -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return sample;
        }, "default", "slow", Duration.ofMillis(50));

        try {
            assertThatThrownBy(store::get)
                    .isInstanceOf(AuthenticationException.class)
                    .satisfies(ex -> assertThat(((AuthenticationException) ex).getDetails())
                            .contains("Timed out"));
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("custom cacheKey and sourceLabel are honored")
    void customCacheKeyAndLabel() {
        JwksKeyStore store = new JwksKeyStore(countingLoader(() -> sample),
                "gw-11", "nrf-a:8443/oauth2/jwks", Duration.ofSeconds(2));

        JWKSet result = store.get();

        assertThat(result).isNotNull();
        assertThat(loadCount.get()).isEqualTo(1);
    }

    private JwkSetLoader countingLoader(java.util.function.Supplier<JWKSet> supplier) {
        return () -> {
            loadCount.incrementAndGet();
            return supplier.get();
        };
    }
}
