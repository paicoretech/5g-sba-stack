package com.paicore.sba.stack.security.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class VerifiedTokenCache {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    record Entry(Principal principal, Date expiration, long cacheUntilEpochMilli) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlSeconds;
    private final int maxSize;

    VerifiedTokenCache(long ttlSeconds, int maxSize) {
        this.ttlSeconds = ttlSeconds;
        this.maxSize = maxSize;
    }

    boolean enabled() {
        return ttlSeconds > 0 && maxSize > 0;
    }

    String keyFor(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is required but not available", ex);
        }
    }

    Entry get(String key) {
        Entry entry = entries.get(key);
        if (Objects.isNull(entry)) {
            return null;
        }
        if (System.currentTimeMillis() >= entry.cacheUntilEpochMilli()) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    void put(String key, Principal principal, Date expiration) {
        if (!enabled() || Objects.isNull(expiration)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cacheUntil = Math.min(expiration.getTime(), now + ttlSeconds * 1000L);
        if (cacheUntil <= now) {
            return;
        }
        if (entries.size() >= maxSize) {
            purgeStale(now);
            if (entries.size() >= maxSize) {
                return;
            }
        }
        entries.put(key, new Entry(principal, expiration, cacheUntil));
    }

    private void purgeStale(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now >= iterator.next().getValue().cacheUntilEpochMilli()) {
                iterator.remove();
            }
        }
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }
}
