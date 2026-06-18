package heapo.query_engine;

import heapo.model.TopNRow;

import java.util.List;

/** Renders query results as JSONL (one JSON object per line). */
public final class JsonlFormatter {

    private JsonlFormatter() {}

    public static String format(List<TopNRow> rows) {
        var sb = new StringBuilder();
        for (var row : rows) {
            sb.append("{\"rank\":").append(row.rank())
              .append(",\"id\":\"#").append(row.denseId()).append('"')
              .append(",\"type\":\"").append(escapeJson(row.className())).append('"')
              .append(",\"retainedSize\":").append(row.retainedSize())
              .append(",\"shallowSize\":").append(row.shallowSize())
              .append("}\n");
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
