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
              .append(",\"shallowSize\":").append(row.shallowSize());
            if (row.description() != null) {
                sb.append(",\"description\":\"").append(descEsc(row.description())).append('"');
            }
            sb.append("}\n");
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
              .append(",\"field\":\"").append(node.field() != null ? esc(node.field()) : "").append('"')
              .append(",\"retainedSize\":").append(node.retainedSize());
            if (node.description() != null)
                sb.append(",\"description\":\"").append(esc(node.description())).append('"');
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

    private static String descEsc(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                               else sb.append(c); }
            }
        }
        return sb.toString();
    }
}
