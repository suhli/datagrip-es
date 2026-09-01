package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Datasource-scoped metadata cache. Completion only reads immutable snapshots;
 * network refresh happens on background threads.
 */
@Service(Service.Level.PROJECT)
public final class EsCompletionMetadataService {
    private static final Logger LOG = Logger.getInstance(EsCompletionMetadataService.class);
    public static final long TTL_MILLIS = 20_000L;

    private final Project project;
    private final ConcurrentHashMap<String, AtomicReference<EsCompletionMetadataSnapshot>> snapshots =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> refreshing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EsCompletionMetadataProvider> providers =
            new ConcurrentHashMap<>();

    public EsCompletionMetadataService(@NotNull Project project) {
        this.project = project;
    }

    public static EsCompletionMetadataService getInstance(Project project) {
        return project.getService(EsCompletionMetadataService.class);
    }

    public void registerProvider(EsCompletionMetadataProvider provider) {
        if (provider == null || provider.datasourceId() == null) return;
        providers.put(provider.datasourceId(), provider);
    }

    public void unregisterProvider(String datasourceId) {
        if (datasourceId == null) return;
        providers.remove(datasourceId);
        snapshots.remove(datasourceId);
        refreshing.remove(datasourceId);
    }

    public EsCompletionMetadataSnapshot snapshot(@Nullable String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            return EsCompletionMetadataSnapshot.EMPTY;
        }
        AtomicReference<EsCompletionMetadataSnapshot> ref =
                snapshots.computeIfAbsent(datasourceId, id -> new AtomicReference<>(EsCompletionMetadataSnapshot.EMPTY));
        EsCompletionMetadataSnapshot current = ref.get();
        if (current.isExpired(System.currentTimeMillis(), TTL_MILLIS)
                || current.indices().isEmpty()) {
            scheduleRefresh(datasourceId, List.of());
        }
        return current;
    }

    public EsCompletionMetadataSnapshot snapshotForIndices(
            @Nullable String datasourceId, List<String> indexPatterns) {
        EsCompletionMetadataSnapshot current = snapshot(datasourceId);
        List<String> concrete = resolveIndices(current, indexPatterns);
        boolean missingFields = concrete.stream().anyMatch(index ->
                current.fields().values().stream().noneMatch(f -> f.indexCoverage().contains(index)));
        if (missingFields && !concrete.isEmpty()) {
            scheduleRefresh(datasourceId, concrete);
        }
        if (concrete.isEmpty()) return current;

        Map<String, EsCompletionMetadataSnapshot.FieldInfo> filtered = new LinkedHashMap<>();
        for (EsCompletionMetadataSnapshot.FieldInfo field : current.fields().values()) {
            boolean matches = false;
            for (String index : concrete) {
                if (field.indexCoverage().contains(index)) {
                    matches = true;
                    break;
                }
            }
            if (matches) filtered.put(field.path(), field);
        }
        return new EsCompletionMetadataSnapshot(
                current.datasourceId(),
                current.esVersion(),
                current.indices(),
                filtered,
                current.loadedAtMillis());
    }

    public void putSnapshot(EsCompletionMetadataSnapshot snapshot) {
        if (snapshot == null || snapshot.datasourceId().isBlank()) return;
        snapshots.computeIfAbsent(snapshot.datasourceId(), id -> new AtomicReference<>())
                .set(snapshot);
    }

    private void scheduleRefresh(@Nullable String datasourceId, List<String> indexNames) {
        if (datasourceId == null || datasourceId.isBlank()) return;
        EsCompletionMetadataProvider provider = providers.get(datasourceId);
        if (provider == null) return;
        AtomicBoolean flag = refreshing.computeIfAbsent(datasourceId, id -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<EsCompletionMetadataSnapshot.IndexObject> targets = provider.listTargets();
                List<String> toLoad = new ArrayList<>(indexNames);
                if (toLoad.isEmpty()) {
                    toLoad.addAll(targets.stream().map(EsCompletionMetadataSnapshot.IndexObject::name).limit(50).toList());
                }
                List<EsCompletionMetadataSnapshot.FieldInfo> fields = provider.loadFields(toLoad);
                EsCompletionMetadataSnapshot previous = snapshot(datasourceId);
                Map<String, EsCompletionMetadataSnapshot.FieldInfo> merged =
                        new LinkedHashMap<>(previous.fields());
                for (EsCompletionMetadataSnapshot.FieldInfo field : fields) {
                    merged.merge(field.path(), field, EsCompletionMetadataService::mergeField);
                }
                putSnapshot(new EsCompletionMetadataSnapshot(
                        datasourceId,
                        provider.esVersion(),
                        targets,
                        merged,
                        System.currentTimeMillis()));
            } catch (Exception e) {
                LOG.debug("Elasticsearch completion metadata refresh failed", e);
            } finally {
                flag.set(false);
            }
        });
    }

    public static List<String> resolveIndices(EsCompletionMetadataSnapshot snapshot, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank() || pattern.equals("*")) {
                snapshot.indices().forEach(i -> result.add(i.name()));
                continue;
            }
            if (!pattern.contains("*") && !pattern.contains("?")) {
                result.add(pattern);
                continue;
            }
            Pattern regex = wildcard(pattern);
            for (EsCompletionMetadataSnapshot.IndexObject index : snapshot.indices()) {
                if (regex.matcher(index.name()).matches()) result.add(index.name());
            }
        }
        return List.copyOf(result);
    }

    private static Pattern wildcard(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private static EsCompletionMetadataSnapshot.FieldInfo mergeField(
            EsCompletionMetadataSnapshot.FieldInfo left,
            EsCompletionMetadataSnapshot.FieldInfo right) {
        Set<String> types = new LinkedHashSet<>(left.types());
        types.addAll(right.types());
        Set<String> coverage = new LinkedHashSet<>(left.indexCoverage());
        coverage.addAll(right.indexCoverage());
        return new EsCompletionMetadataSnapshot.FieldInfo(
                left.path(), types, coverage, left.multiField() || right.multiField());
    }
}
