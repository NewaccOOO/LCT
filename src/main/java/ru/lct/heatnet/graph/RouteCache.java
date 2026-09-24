package ru.lct.heatnet.graph;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Общий кэш маршрутизаторов одного расчёта с потолком в байтах: веса от точек запроса до узлов графа и таблицы
 * Дейкстры. Раньше у каждого маршрутизатора был свой кэш с лимитом числа записей, а маршрутизаторы живут до конца
 * расчёта, поэтому память росла с числом областей и узлов. При переполнении вытесняются давно не запрошенные записи.
 * Кэш только ускоряет: вытесненная запись при следующем запросе считается заново так же, выход не меняется.
 */
public final class RouteCache {
    /** Потолок по умолчанию, МБ; свойство {@code heatnet.route.cache.mb}. */
    public static final long DEFAULT_MB = Long.getLong("heatnet.route.cache.mb", 1024);
    // заголовок массива, ключ со ссылкой на маршрутизатор и координатами, узел LinkedHashMap
    private static final long ENTRY_OVERHEAD = 160;

    private final long budgetBytes;
    private final LinkedHashMap<List<Object>, Sized> entries = new LinkedHashMap<>(64, 0.75f, true);
    /**
     * Записи, которые сейчас считает какая-то нить: деревья кандидатов одного блока строятся параллельно и
     * одновременно просили буфер зоны одного здания, на датасете организаторов это были две пятых буферов.
     */
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
        // последняя запись остаётся всегда: её только что запросили
        Iterator<Map.Entry<List<Object>, Sized>> eldest = entries.entrySet().iterator();
        while (bytes > budgetBytes && entries.size() > 1) {
            bytes -= eldest.next().getValue().bytes;
            eldest.remove();
            evictions++;
        }
    }

    /**
     * Значение из кэша или посчитанное. Если его уже считает другая нить, ждёт её результата; если та упала или
     * получила null, считает сама, как без ожидания.
     */
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

    /** Занято и пик в МБ, число записей и вытеснений — для строки итога поиска. */
    public synchronized String stats() {
        return String.format("cache=%dMB peak=%dMB entries=%d evicted=%d",
                bytes >> 20, peakBytes >> 20, entries.size(), evictions);
    }
}
