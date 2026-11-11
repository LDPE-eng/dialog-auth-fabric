package ru.ldpe.dialogauth;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class RateLimiter {
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final long RETENTION = Duration.ofMinutes(15).toNanos();
    private final LongSupplier clock;
    private final int capacity;
    private final LinkedHashMap<String, Entry> principals = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Entry> ips = new LinkedHashMap<>(16, .75f, true);

    public RateLimiter(LongSupplier clock, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.clock = clock;
        this.capacity = capacity;
    }

    public long acquire(UUID uuid, String ip) {
        return acquire("uuid:" + uuid, ip);
    }

    public synchronized long acquire(String principal, String ip) {
        long now = clock.getAsLong();
        prune(principals, now);
        prune(ips, now);
        Entry user = principals.get(principal);
        Entry address = ips.get(ip);
        if ((user == null && principals.size() >= capacity) || (address == null && ips.size() >= capacity)) {
            return 60000;
        }
        if (user == null) { user = new Entry(now); principals.put(principal, user); }
        if (address == null) { address = new Entry(now); ips.put(ip, address); }
        user.touched = address.touched = now;
        long wait = Math.max(user.next - now, address.next - now);
        if (wait > 0) return (wait + 999999) / 1000000;
        user.next = address.next = now + SECOND;
        return 0;
    }

    public void failed(UUID uuid, String ip) {
        failed("uuid:" + uuid, ip);
    }

    public synchronized void failed(String principal, String ip) {
        long now = clock.getAsLong();
        penalize(principals.get(principal), now);
        penalize(ips.get(ip), now);
    }

    private static void penalize(Entry entry, long now) {
        if (entry == null) return;
        entry.failures = Math.min(7, entry.failures + 1);
        entry.touched = now;
        entry.next = Math.max(entry.next, now + Math.min(60, 1L << (entry.failures - 1)) * SECOND);
    }

    private static <K> void prune(LinkedHashMap<K, Entry> entries, long now) {
        var iterator = entries.entrySet().iterator();
        for (int i = 0; i < 32 && iterator.hasNext(); i++) {
            Entry entry = iterator.next().getValue();
            if (now - entry.touched < RETENTION) break;
            iterator.remove();
        }
    }

    private static final class Entry {
        long next;
        long touched;
        int failures;
        Entry(long now) { next = touched = now; }
    }
}
