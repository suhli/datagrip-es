package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class EsSqlException {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EsSqlException() {}

    static SQLException from(Transport.Response response, String method, String path) {
        String type = "";
        String reason = "";
        List<String> rootCauses = new ArrayList<>();
        try {
            JsonNode error = JSON.readTree(response.body()).path("error");
            if (error.isTextual()) {
                reason = error.asText();
            } else {
                type = error.path("type").asText("");
                reason = error.path("reason").asText("");
                for (JsonNode cause : error.path("root_cause")) {
                    String causeType = cause.path("type").asText("");
                    String causeReason = cause.path("reason").asText("");
                    if (!causeType.isBlank() || !causeReason.isBlank()) {
                        rootCauses.add((causeType + ": " + causeReason).trim());
                    }
                }
            }
        } catch (Exception ignored) {
            reason = "non-JSON error response";
        }
        StringBuilder message = new StringBuilder("Elasticsearch HTTP ")
                .append(response.status()).append(" for ").append(method).append(' ').append(path);
        if (!type.isBlank()) message.append(" [").append(type).append(']');
        if (!reason.isBlank()) message.append(": ").append(reason);
        if (!rootCauses.isEmpty()) message.append("; root_cause=").append(String.join(" | ", rootCauses));
        String sqlState = response.status() == 401 || response.status() == 403 ? "28000" : "HY000";
        return new SQLException(message.toString(), sqlState, response.status());
    }
}
