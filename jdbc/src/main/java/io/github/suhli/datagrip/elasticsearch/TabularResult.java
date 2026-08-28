package io.github.suhli.datagrip.elasticsearch;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record TabularResult(List<Column> columns, List<List<Object>> rows) {
    public TabularResult {
        columns = List.copyOf(columns);
        rows = rows.stream()
                .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                .toList();
    }

    public record Column(String label, int jdbcType, String typeName) {}
}
