package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Thread-safe short-TTL cache for Elasticsearch metadata. */
final class MetadataCache {
    static final Duration DEFAULT_TTL = Duration.ofSeconds(3);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Duration ttl;
    private List<IndexInfo> indices;
    private long indicesLoadedAt;
    private final Map<String, CachedMapping> indexMappings = new LinkedHashMap<>();

    MetadataCache() {
        this(DEFAULT_TTL);
    }

    MetadataCache(Duration ttl) {
        this.ttl = ttl;
    }

    List<IndexInfo> indicesIfFresh() {
        lock.readLock().lock();
        try {
            return isFresh(indicesLoadedAt) ? indices : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    void putIndices(List<IndexInfo> value) {
        lock.writeLock().lock();
        try {
            indices = List.copyOf(value);
            indicesLoadedAt = System.nanoTime();
        } finally {
            lock.writeLock().unlock();
        }
    }

    JsonNode mappingIfFresh(String index) {
        lock.readLock().lock();
        try {
            CachedMapping cached = indexMappings.get(index);
            return cached != null && isFresh(cached.loadedAt) ? cached.mapping() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    void putMapping(String index, JsonNode mapping) {
        lock.writeLock().lock();
        try {
            indexMappings.put(index, new CachedMapping(mapping, System.nanoTime()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    void refresh() {
        invalidate();
    }

    void invalidate() {
        lock.writeLock().lock();
        try {
            indices = null;
            indicesLoadedAt = 0;
            indexMappings.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    void invalidateIndex(String index) {
        lock.writeLock().lock();
        try {
            indexMappings.remove(index);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isFresh(long loadedAt) {
        return loadedAt > 0 && System.nanoTime() - loadedAt <= ttl.toNanos();
    }

    record IndexInfo(String name, String health, String status, String docsCount, String storeSize) {}

    private record CachedMapping(JsonNode mapping, long loadedAt) {}
}
