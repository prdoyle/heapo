package heapo.query_engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import heapo.model.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

/** Renders query results as JSONL (one JSON object per line). */
public final class JsonlFormatter {

    private JsonlFormatter() {}

    static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ── Output shape records ──────────────────────────────────────────────────

    private record TopNJson(int rank, String id, String type,
                            long retainedSize, long shallowSize, String description) {}

    private record CountJson(String className, long count) {}

    private record ClassJson(String id, String className, long instanceCount) {}

    private record ExplainJson(int depth, String id, String type, String field,
                               long retainedSize, String description, String notes) {}

    private record AggregateJson(String className, String func, long retainedSize) {}

    private record ReadJson(String id, String content) {}

    // ── Public format methods ─────────────────────────────────────────────────

    public static String formatTopN(List<TopNRow> rows) throws IOException {
        var sb = new StringBuilder();
        for (var row : rows) {
            sb.append(MAPPER.writeValueAsString(new TopNJson(
                row.rank(), "i" + row.denseId(), row.className(),
                row.retainedSize(), row.shallowSize(), row.description())));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatCount(String className, long count) throws IOException {
        return MAPPER.writeValueAsString(new CountJson(className, count)) + "\n";
    }

    public static String formatClasses(List<ClassInfo> classes) throws IOException {
        var sb = new StringBuilder();
        for (var c : classes) {
            sb.append(MAPPER.writeValueAsString(new ClassJson(
                "i" + c.denseId(), c.className(), c.instanceCount())));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatExplain(List<ExplainNode> path) throws IOException {
        var sb = new StringBuilder();
        for (var node : path) {
            sb.append(MAPPER.writeValueAsString(new ExplainJson(
                node.depth(), "i" + node.denseId(), node.className(),
                node.field() != null ? node.field() : "",
                node.retainedSize(), node.description(), node.notes())));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatAggregateRetainedSize(String className, String func, long value)
            throws IOException {
        return MAPPER.writeValueAsString(new AggregateJson(className, func, value)) + "\n";
    }

    public static String formatInspect(List<FieldRow> rows) throws IOException {
        var sb = new StringBuilder();
        for (var r : rows) {
            var m = new LinkedHashMap<String, Object>();
            m.put("field", r.fieldName());
            if (r.isNullRef()) {
                m.put("id", "null");
            } else if (r.isObject()) {
                m.put("id", "i" + r.refDenseId());
                m.put("type", r.className());
                m.put("retainedSize", r.retainedSize());
                m.put("shallowSize", r.shallowSize());
                if (r.description() != null) m.put("description", r.description());
            } else if (r.isPrimitive()) {
                m.put("type", r.primType());
                m.put("description", r.description() != null ? r.description() : "");
            }
            sb.append(MAPPER.writeValueAsString(m));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatRead(int denseId, String content) throws IOException {
        return MAPPER.writeValueAsString(new ReadJson("i" + denseId, content)) + "\n";
    }
}
