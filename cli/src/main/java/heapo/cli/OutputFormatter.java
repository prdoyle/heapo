package heapo.cli;

import java.util.*;
import java.util.stream.Collectors;

/** Converts JSONL output to an alternate format. */
final class OutputFormatter {

    enum Format { JSONL, JSON, HUMAN }

    private OutputFormatter() {}

    /**
     * Converts JSONL (one JSON object per line) to the requested format.
     * For JSONL the input is returned unchanged.
     */
    static String convert(String jsonl, Format format) {
        return switch (format) {
            case JSONL  -> jsonl;
            case JSON   -> toJsonArray(jsonl);
            case HUMAN  -> toHuman(jsonl);
        };
    }

    // ── JSON array ────────────────────────────────────────────────────────────

    private static String toJsonArray(String jsonl) {
        List<String> lines = jsonl.lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty())
            .collect(Collectors.toList());
        if (lines.isEmpty()) return "[]\n";
        return "[\n  " + String.join(",\n  ", lines) + "\n]\n";
    }

    // ── Human-readable ────────────────────────────────────────────────────────

    private static String toHuman(String jsonl) {
        List<Map<String, String>> rows = parseJsonl(jsonl);
        if (rows.isEmpty()) return "(no results)\n";

        // Determine columns from the union of all rows' keys (preserving first-seen order)
        List<String> cols = new ArrayList<>();
        for (var row : rows)
            for (var key : row.keySet())
                if (!cols.contains(key)) cols.add(key);

        // Compute column widths
        int[] widths = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) widths[i] = cols.get(i).length();
        for (var row : rows) {
            for (int i = 0; i < cols.size(); i++) {
                String v = row.getOrDefault(cols.get(i), "");
                widths[i] = Math.max(widths[i], v.length());
            }
        }

        // Render
        var sb = new StringBuilder();
        // Header
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(pad(cols.get(i), widths[i], NUMERIC_COLS.contains(cols.get(i))));
        }
        sb.append('\n');
        // Separator
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(widths[i]));
        }
        sb.append('\n');
        // Rows
        for (var row : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append("  ");
                sb.append(pad(row.getOrDefault(cols.get(i), ""), widths[i], NUMERIC_COLS.contains(cols.get(i))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final Set<String> NUMERIC_COLS =
        Set.of("rank", "retainedSize", "shallowSize", "depth", "instanceCount", "count");

    private static String pad(String s, int width, boolean rightAlign) {
        int pad = width - s.length();
        if (pad <= 0) return s;
        return rightAlign ? " ".repeat(pad) + s : s + " ".repeat(pad);
    }

    // Very simple JSONL parser: handles flat objects with string/number values only.
    private static List<Map<String, String>> parseJsonl(String jsonl) {
        var result = new ArrayList<Map<String, String>>();
        for (String line : jsonl.lines().toList()) {
            line = line.strip();
            if (line.isEmpty() || !line.startsWith("{")) continue;
            var map = new LinkedHashMap<String, String>();
            // strip outer braces
            String inner = line.substring(1, line.length() - 1).strip();
            for (String kv : splitTopLevel(inner)) {
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).strip().replaceAll("^\"|\"$", "");
                String val = kv.substring(colon + 1).strip().replaceAll("^\"|\"$", "");
                map.put(key, val);
            }
            result.add(map);
        }
        return result;
    }

    // Split a flat JSON object body on top-level commas (no nesting support needed here).
    private static List<String> splitTopLevel(String s) {
        var parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i).strip());
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) parts.add(s.substring(start).strip());
        return parts;
    }
}
