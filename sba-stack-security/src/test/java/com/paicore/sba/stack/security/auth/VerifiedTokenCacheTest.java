package com.paicore.sba.stack.security.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedTokenCacheTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbWYifQ.signature";

    private Principal principal(String id) {
        return Principal.builder().id(id).issuer("http://nrf:9096").scopes(Set.of("nsmsf-sms")).build();
    }

    private Date inSeconds(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1000L);
    }

    @Test
    @DisplayName("a stored entry is returned for the same token")
    void returnsStoredEntry() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        String key = cache.keyFor(TOKEN);

        cache.put(key, principal("amf"), inSeconds(300));

        VerifiedTokenCache.Entry hit = cache.get(key);
        assertNotNull(hit);
        assertEquals("amf", hit.principal().getId());
    }

    @Test
    @DisplayName("a different token never hits another token's entry")
    void differentTokenIsAMiss() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        cache.put(cache.keyFor(TOKEN), principal("amf"), inSeconds(300));

        assertNotEquals(cache.keyFor(TOKEN), cache.keyFor(TOKEN + "x"));
        assertNull(cache.get(cache.keyFor(TOKEN + "x")));
    }

    @Test
    @DisplayName("the key never contains the raw token")
    void keyDoesNotLeakTheToken() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        String key = cache.keyFor(TOKEN);

        assertEquals(64, key.length());
        assertFalse(key.contains(TOKEN));
    }

    @Test
    @DisplayName("the entry lifetime is capped by the ttl, not by the token expiry")
    void ttlCapsTheEntryLifetime() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        String key = cache.keyFor(TOKEN);
        Date farFuture = inSeconds(3600);

        cache.put(key, principal("amf"), farFuture);

        VerifiedTokenCache.Entry hit = cache.get(key);
        assertNotNull(hit);
        assertTrue(hit.cacheUntilEpochMilli() < farFuture.getTime());
        assertTrue(hit.cacheUntilEpochMilli() <= System.currentTimeMillis() + 30_000L);
    }

    @Test
    @DisplayName("the entry lifetime never outlives the token expiry")
    void tokenExpiryCapsTheEntryLifetime() {
        VerifiedTokenCache cache = new VerifiedTokenCache(300, 100);
        String key = cache.keyFor(TOKEN);
        Date soon = inSeconds(5);

        cache.put(key, principal("amf"), soon);

        assertEquals(soon.getTime(), cache.get(key).cacheUntilEpochMilli());
    }

    @Test
    @DisplayName("an already expired token is not stored")
    void doesNotStoreExpiredTokens() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        String key = cache.keyFor(TOKEN);

        cache.put(key, principal("amf"), inSeconds(-1));

        assertNull(cache.get(key));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("a stale entry is a miss and is dropped")
    void staleEntryIsAMiss() throws InterruptedException {
        VerifiedTokenCache cache = new VerifiedTokenCache(300, 100);
        String key = cache.keyFor(TOKEN);
        cache.put(key, principal("amf"), new Date(System.currentTimeMillis() + 40L));

        Thread.sleep(60L);

        assertNull(cache.get(key));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("the cache never grows past its maximum size")
    void respectsMaxSize() {
        VerifiedTokenCache cache = new VerifiedTokenCache(300, 3);

        for (int i = 0; i < 50; i++) {
            cache.put(cache.keyFor(TOKEN + i), principal("amf" + i), inSeconds(300));
        }

        assertTrue(cache.size() <= 3, "size was " + cache.size());
    }

    @Test
    @DisplayName("a zero ttl disables the cache")
    void zeroTtlDisablesTheCache() {
        VerifiedTokenCache cache = new VerifiedTokenCache(0, 100);
        String key = cache.keyFor(TOKEN);

        cache.put(key, principal("amf"), inSeconds(300));

        assertFalse(cache.enabled());
        assertNull(cache.get(key));
    }

    @Test
    @DisplayName("a null expiry is not stored")
    void doesNotStoreWithoutExpiry() {
        VerifiedTokenCache cache = new VerifiedTokenCache(30, 100);
        String key = cache.keyFor(TOKEN);

        cache.put(key, principal("amf"), null);

        assertNull(cache.get(key));
    }
}
