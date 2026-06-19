package heapo.query_engine;

import heapo.model.*;

import java.util.List;

/** Renders query results as JSONL (one JSON object per line). */
public final class JsonlFormatter {

    private JsonlFormatter() {}

    public static String formatTopN(List<TopNRow> rows) {
        var sb = new StringBuilder();
        for (var row : rows) {
            sb.append("{\"rank\":").append(row.rank())
              .append(",\"id\":\"i").append(row.denseId()).append('"')
              .append(",\"type\":\"").append(esc(row.className())).append('"')
              .append(",\"retainedSize\":").append(row.retainedSize())
              .append(",\"shallowSize\":").append(row.shallowSize())
              .append("}\n");
        }
        return sb.toString();
    }

    public static String formatCount(String className, long count) {
        return "{\"className\":\"" + esc(className) + "\",\"count\":" + count + "}\n";
    }

    public static String formatClasses(List<ClassInfo> classes) {
        var sb = new StringBuilder();
        for (var c : classes) {
            sb.append("{\"id\":\"i").append(c.denseId()).append('"')
              .append(",\"className\":\"").append(esc(c.className())).append('"')
              .append(",\"instanceCount\":").append(c.instanceCount())
              .append("}\n");
        }
        return sb.toString();
    }

    public static String formatExplain(List<ExplainNode> path) {
        var sb = new StringBuilder();
        for (var node : path) {
            sb.append("{\"depth\":").append(node.depth())
              .append(",\"id\":\"i").append(node.denseId()).append('"')
              .append(",\"type\":\"").append(esc(node.className())).append('"')
              .append(",\"retainedSize\":").append(node.retainedSize());
            if (node.notes() != null)
                sb.append(",\"notes\":\"").append(esc(node.notes())).append('"');
            sb.append("}\n");
        }
        return sb.toString();
    }

    public static String formatAggregateRetainedSize(String className, String func, long value) {
        return "{\"className\":\"" + esc(className) + "\",\"func\":\"" + func
            + "\",\"retainedSize\":" + value + "}\n";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
