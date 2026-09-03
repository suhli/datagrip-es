package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

/** Central version gate for every generated completion candidate. */
public final class EsSchemaAvailability {
    public enum Status { AVAILABLE, DEPRECATED, UNAVAILABLE }

    private EsSchemaAvailability() {}

    public static boolean isAvailable(EsSchemaModels.Endpoint endpoint, String clusterVersion) {
        return endpoint != null && isAvailable(endpoint.minVersion(), clusterVersion);
    }

    public static boolean isAvailable(EsSchemaModels.QueryParam parameter, String clusterVersion) {
        return parameter != null && isAvailable(parameter.minVersion(), clusterVersion);
    }

    public static boolean isAvailable(EsSchemaModels.DslNode node, String clusterVersion) {
        return node != null && isAvailable(node.minVersion(), clusterVersion);
    }

    public static boolean isAvailable(String minimumVersion, String clusterVersion) {
        return status(minimumVersion, null, false, clusterVersion) != Status.UNAVAILABLE;
    }

    public static Status status(EsSchemaModels.Endpoint endpoint, String clusterVersion) {
        return endpoint == null ? Status.UNAVAILABLE : status(
                endpoint.minVersion(), endpoint.deprecatedVersion(), endpoint.deprecated(), clusterVersion);
    }

    public static Status status(EsSchemaModels.QueryParam parameter, String clusterVersion) {
        return parameter == null ? Status.UNAVAILABLE : status(
                parameter.minVersion(), parameter.deprecatedVersion(), parameter.deprecated(), clusterVersion);
    }

    public static Status status(EsSchemaModels.DslNode node, String clusterVersion) {
        return node == null ? Status.UNAVAILABLE : status(
                node.minVersion(), node.deprecatedVersion(), node.deprecated(), clusterVersion);
    }

    public static Status status(
            String minimumVersion,
            String deprecatedVersion,
            boolean deprecated,
            String clusterVersion) {
        if (clusterVersion == null || clusterVersion.isBlank()) return Status.AVAILABLE;
        if (minimumVersion != null && !minimumVersion.isBlank()
                && compare(clusterVersion, minimumVersion) < 0) {
            return Status.UNAVAILABLE;
        }
        if (deprecatedVersion != null && !deprecatedVersion.isBlank()) {
            return compare(clusterVersion, deprecatedVersion) >= 0
                    ? Status.DEPRECATED : Status.AVAILABLE;
        }
        return deprecated ? Status.DEPRECATED : Status.AVAILABLE;
    }

    static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] numbers(String version) {
        String core = version == null ? "" : version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }
}
