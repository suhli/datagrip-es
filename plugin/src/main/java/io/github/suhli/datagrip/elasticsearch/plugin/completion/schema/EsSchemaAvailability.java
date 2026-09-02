package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

/** Central version gate for every generated completion candidate. */
public final class EsSchemaAvailability {
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
        if (minimumVersion == null || minimumVersion.isBlank()
                || clusterVersion == null || clusterVersion.isBlank()) {
            return true;
        }
        return compare(clusterVersion, minimumVersion) >= 0;
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
