package io.github.suhli.datagrip.elasticsearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record TabularResult(
        List<Column> columns,
        List<List<Object>> rows,
        String rawBody,
        boolean structured) {
    public TabularResult(List<Column> columns, List<List<Object>> rows) {
        this(columns, rows, null, true);
    }

    public TabularResult {
        columns = List.copyOf(columns);
        rows = rows.stream()
                .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                .toList();
    }

    public record Column(String label, int jdbcType, String typeName) {}
}
