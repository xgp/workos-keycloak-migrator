package io.phasetwo.wkm.common.state;

import io.phasetwo.wkm.common.sync.SyncAction;
import io.phasetwo.wkm.common.sync.SyncResult;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-entity-type counters with thread-safe increments. */
public final class Counters {

    private final Map<String, EnumMap<SyncAction, AtomicInteger>> byType = new LinkedHashMap<>();

    public synchronized void record(SyncResult result) {
        byType.computeIfAbsent(result.entityType(), k -> new EnumMap<>(SyncAction.class))
                .computeIfAbsent(result.action(), k -> new AtomicInteger())
                .incrementAndGet();
    }

    public synchronized Map<String, Map<SyncAction, Integer>> snapshot() {
        Map<String, Map<SyncAction, Integer>> out = new LinkedHashMap<>();
        byType.forEach((k, v) -> {
            Map<SyncAction, Integer> per = new LinkedHashMap<>();
            v.forEach((a, n) -> per.put(a, n.get()));
            out.put(k, per);
        });
        return out;
    }

    public synchronized int total(SyncAction action) {
        int sum = 0;
        for (EnumMap<SyncAction, AtomicInteger> m : byType.values()) {
            AtomicInteger ai = m.get(action);
            if (ai != null) sum += ai.get();
        }
        return sum;
    }
}
