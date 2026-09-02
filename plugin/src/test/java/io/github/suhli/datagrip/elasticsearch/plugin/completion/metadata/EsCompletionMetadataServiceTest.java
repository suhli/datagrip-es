package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsCompletionMetadataServiceTest {
    @Test
    void coalescesDifferentIndicesWithoutDroppingTheSecondRequest() {
        EsCompletionMetadataService service = new EsCompletionMetadataService(Runnable::run);
        CoalescingProvider provider = new CoalescingProvider(service);
        service.registerProvider("ds", "one", provider);

        // Loads targets first, then index-a. The provider requests index-b while
        // index-a is still in flight, exercising the worker's pending queue.
        service.snapshotForIndices("ds", List.of("index-a"));

        assertEquals(List.of(List.of("index-a"), List.of("index-b")), provider.loads);
        EsCompletionMetadataSnapshot a = service.snapshotForIndices("ds", List.of("index-a"));
        EsCompletionMetadataSnapshot b = service.snapshotForIndices("ds", List.of("index-b"));
        assertTrue(a.fields().containsKey("field_a"));
        assertFalse(a.fields().containsKey("field_b"));
        assertTrue(b.fields().containsKey("field_b"));
        assertFalse(b.fields().containsKey("field_a"));
    }

    @Test
    void providerReplacementClosesOldProviderAndInvalidatesSnapshot() {
        EsCompletionMetadataService service = new EsCompletionMetadataService(Runnable::run);
        CloseTrackingProvider oldProvider = new CloseTrackingProvider("ds");
        CloseTrackingProvider replacement = new CloseTrackingProvider("ds");
        service.registerProvider("ds", "old", oldProvider);
        service.putSnapshot(new EsCompletionMetadataSnapshot(
                "ds", "8.0.0",
                List.of(new EsCompletionMetadataSnapshot.IndexObject("old-index", "index")),
                java.util.Map.of(), System.currentTimeMillis()));

        service.registerProvider("ds", "new", replacement);

        assertTrue(oldProvider.closed.get());
        assertFalse(replacement.closed.get());
        assertFalse(service.hasProvider("ds", "old"));
        assertTrue(service.hasProvider("ds", "new"));
        assertTrue(service.snapshot("ds").indices().isEmpty());
        service.dispose();
        assertTrue(replacement.closed.get());
    }

    @Test
    void targetRefreshDoesNotPreloadMappingsWithoutAnIndexContext() {
        EsCompletionMetadataService service = new EsCompletionMetadataService(Runnable::run);
        CloseTrackingProvider provider = new CloseTrackingProvider("ds");
        service.registerProvider("ds", "one", provider);

        EsCompletionMetadataSnapshot snapshot = service.snapshot("ds");

        assertTrue(snapshot.indices().isEmpty()); // completion returns the pre-refresh snapshot immediately
        assertEquals(1, provider.calls.get());
        assertEquals(0, provider.mappingCalls.get());
        assertEquals(1, service.snapshot("ds").indices().size());
    }

    private static final class CoalescingProvider implements EsCompletionMetadataProvider {
        private final EsCompletionMetadataService service;
        private final List<List<String>> loads = new ArrayList<>();
        private boolean requestedSecond;

        private CoalescingProvider(EsCompletionMetadataService service) {
            this.service = service;
        }

        @Override
        public String datasourceId() {
            return "ds";
        }

        @Override
        public String esVersion() {
            return "8.17.0";
        }

        @Override
        public List<EsCompletionMetadataSnapshot.IndexObject> listTargets() {
            return List.of(
                    new EsCompletionMetadataSnapshot.IndexObject("index-a", "index"),
                    new EsCompletionMetadataSnapshot.IndexObject("index-b", "index"));
        }

        @Override
        public List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) {
            loads.add(List.copyOf(indexNames));
            if (indexNames.equals(List.of("index-a")) && !requestedSecond) {
                requestedSecond = true;
                service.snapshotForIndices("ds", List.of("index-b"));
                service.snapshotForIndices("ds", List.of("index-b"));
            }
            String index = indexNames.getFirst();
            String field = index.equals("index-a") ? "field_a" : "field_b";
            return List.of(new EsCompletionMetadataSnapshot.FieldInfo(
                    field, Set.of("keyword"), Set.of(index), false));
        }
    }

    private static final class CloseTrackingProvider implements EsCompletionMetadataProvider {
        private final String id;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger mappingCalls = new AtomicInteger();

        private CloseTrackingProvider(String id) {
            this.id = id;
        }

        @Override
        public String datasourceId() {
            return id;
        }

        @Override
        public String esVersion() {
            return "";
        }

        @Override
        public List<EsCompletionMetadataSnapshot.IndexObject> listTargets() {
            calls.incrementAndGet();
            return List.of(new EsCompletionMetadataSnapshot.IndexObject("index-a", "index"));
        }

        @Override
        public List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) {
            mappingCalls.incrementAndGet();
            return List.of();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
