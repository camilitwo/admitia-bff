package cl.mtn.admitiabff.util;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.StringJoiner;

public final class CsvUtils {
    private CsvUtils() {
    }

    public static String toCsv(Collection<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        rows.forEach(row -> headers.addAll(row.keySet()));
        StringBuilder builder = new StringBuilder();
        builder.append(String.join(",", headers)).append('\n');
        for (Map<String, Object> row : rows) {
            StringJoiner joiner = new StringJoiner(",");
            for (String header : headers) {
                Object value = row.get(header);
                String cell = value == null ? "" : value.toString().replace("\"", "\"\"");
                joiner.add("\"" + cell + "\"");
            }
            builder.append(joiner).append('\n');
        }
        return builder.toString();
    }
}
