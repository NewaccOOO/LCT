package ru.lct.heatnet.graph;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class RouteCache {
    public static final long DEFAULT_MB = Long.getLong("heatnet.route.cache.mb",
            Math.min(1024, Runtime.getRuntime().maxMemory() / 2 / (1024 * 1024)));
    private static final long ENTRY_OVERHEAD = 160;

    private final long budgetBytes;
    private final LinkedHashMap<List<Object>, Sized> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<List<Object>, CompletableFuture<Object>> running = new ConcurrentHashMap<>();
    private long bytes;
    private long peakBytes;
    private long evictions;

    private static final class Sized {
        final Object value;
        final long bytes;

        Sized(Object value, long bytes) {
            this.value = value;
            this.bytes = bytes;
        }
    }

    public RouteCache(long budgetMb) {
        this.budgetBytes = budgetMb * 1024 * 1024;
    }

    @SuppressWarnings("unchecked")
    synchronized <T> T get(List<Object> key) {
        Sized sized = entries.get(key);
        return sized == null ? null : (T) sized.value;
    }

    synchronized void put(List<Object> key, Object value, long valueBytes) {
        Sized old = entries.put(key, new Sized(value, valueBytes + ENTRY_OVERHEAD));
        bytes += valueBytes + ENTRY_OVERHEAD - (old == null ? 0 : old.bytes);
        peakBytes = Math.max(peakBytes, bytes);
        Iterator<Map.Entry<List<Object>, Sized>> eldest = entries.entrySet().iterator();
        while (bytes > budgetBytes && entries.size() > 1) {
            bytes -= eldest.next().getValue().bytes;
            eldest.remove();
            evictions++;
        }
    }

    @SuppressWarnings("unchecked")
    <T> T computeIfAbsent(List<Object> key, long valueBytes, Supplier<T> compute) {
        T cached = get(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> other = running.putIfAbsent(key, mine);
        if (other != null) {
            T value = (T) other.handle((result, error) -> error == null ? result : null).join();
            return value != null ? value : compute.get();
        }
        try {
            T value = compute.get();
            put(key, value, valueBytes);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(key);
        }
    }

    public synchronized String stats() {
        return String.format("cache=%dMB peak=%dMB entries=%d evicted=%d",
                bytes >> 20, peakBytes >> 20, entries.size(), evictions);
    }
}
