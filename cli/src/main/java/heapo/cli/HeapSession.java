package heapo.cli;

import heapo.indexes.IndexRegistry;
import heapo.model.*;
import heapo.query_engine.*;
import heapo.session.*;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Top-level session: ties together index access, query execution, history, and THAT.
 *
 * <p>Call {@link #execute(String)} for each user command; it returns JSONL output.
 */
public final class HeapSession implements AutoCloseable {

    private final UnpackedHeap    heap;
    private final IndexRegistry   registry;
    private final SessionDb       db;
    private final HistoryManager  history;
    private final NamesManager    names;
    private final UserTableManager tables;
    private final SqlRouter       sql;

    private Answer that    = VoidAnswer.INSTANCE;
    private int thatHistId = -1;  // history ID of the command that last set `that`

    private static final int IMPLICIT_DISPLAY_N = 10;

    public HeapSession(UnpackedHeap heap, IndexRegistry registry, Path dbPath)
            throws SQLException {
        this.heap     = heap;
        this.registry = registry;
        this.db       = SessionDb.open(dbPath);
        this.history  = db.history();
        this.names    = db.names();
        this.tables   = db.tables();
        this.sql      = db.sql();
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    /**
     * Execute a command line.  Returns JSONL output (may be empty for void commands).
     */
    public String execute(String command) throws IOException, SQLException {
        String trimmed = command.strip();
        if (trimmed.isEmpty()) return "";

        // ── Session commands ──────────────────────────────────────────────────
        if (trimmed.matches("(?i)NAMES(\\s+MATCHING\\s+\\S+)?")) return handleNames(trimmed);
        if (trimmed.matches("(?i)EXPLAIN\\s+\\S+") && !trimmed.split("\\s+")[1].startsWith("#"))
            return handleExplainName(trimmed.split("\\s+")[1]);
        if (trimmed.equalsIgnoreCase("THAT")) {
            if (that instanceof VoidAnswer)    return "{\"error\":\"THAT is empty\"}\n";
            if (that instanceof BitSetAnswer b) return displayBitSet(b.bits());
            if (that instanceof TableAnswer t)  return sql.selectAsJsonl(t.sqlTableName());
            return "{\"error\":\"cannot display THAT\"}\n";
        }
        if (trimmed.equalsIgnoreCase("UNDO"))  return handleUndo();
        if (trimmed.matches("@\\d+"))          return handleRecall(Integer.parseInt(trimmed.substring(1)));
        if (trimmed.matches("(?i)HISTORY(\\s+\\d+)?")) return handleHistory(trimmed);
        if (trimmed.matches("(?i)CALL\\s+THAT\\s+\\S+")) return handleCallThat(trimmed);
        if (trimmed.matches("(?i)CALL\\s+@\\d+\\s+\\S+")) return handleCallById(trimmed);
        if (trimmed.matches("(?i)FORGET\\s+\\S+")) return handleForget(trimmed);

        // ── SQL query ─────────────────────────────────────────────────────────
        if (SqlRouter.isSql(trimmed)) return handleSql(trimmed);

        // ── DSL query ─────────────────────────────────────────────────────────
        return handleQuery(trimmed);
    }

    public Answer getThat()         { return that;  }
    public NamesManager names()     { return names; }
    public void clearSession()      { db.truncateSession(); }

    // ── Session command handlers ──────────────────────────────────────────────

    private String handleNames(String cmd) {
        String[] parts = cmd.strip().split("\\s+");
        String glob = parts.length >= 3 && parts[1].equalsIgnoreCase("MATCHING") ? parts[2] : null;
        var all = names.all();
        var sb = new StringBuilder();
        for (var e : all.entrySet()) {
            if (glob == null || matchNameGlob(e.getKey(), glob)) {
                sb.append("{\"name\":\"").append(escJson(e.getKey()))
                  .append("\",\"historyId\":").append(e.getValue()).append("}\n");
            }
        }
        return sb.isEmpty() ? "{\"names\":[]}\n" : sb.toString();
    }

    private String handleExplainName(String name) throws SQLException {
        var histIdOpt = names.resolve(name);
        if (histIdOpt.isEmpty())
            return "{\"error\":\"Unknown name: '" + escJson(name) + "'\"}\n";
        int histId = histIdOpt.get();
        var entryOpt = history.findById(histId);
        if (entryOpt.isEmpty())
            return "{\"error\":\"History @" + histId + " not found\"}\n";
        var entry = entryOpt.get();
        String type = entry.bitsetFile() != null ? "bitset"
                    : entry.sqlTable() != null   ? "table"
                    : "other";
        var sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escJson(name)).append('"')
          .append(",\"histId\":\"@").append(histId).append('"')
          .append(",\"type\":\"").append(type).append('"')
          .append(",\"command\":\"").append(escJson(entry.command())).append('"');
        if (entry.input1() != null) sb.append(",\"derivedFrom\":\"@").append(entry.input1()).append('"');
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean matchNameGlob(String name, String glob) {
        return name.matches(glob.replace(".", "\\.").replace("*", ".*").replace("?", "."));
    }

    private String handleHistory(String cmd) {
        int n = 10;
        String[] parts = cmd.split("\\s+");
        if (parts.length >= 2) n = Integer.parseInt(parts[1]);

        var entries = history.recent(n);
        var sb = new StringBuilder();
        for (var e : entries) {
            sb.append("{\"id\":\"@").append(e.id()).append('"')
              .append(",\"command\":\"").append(escJson(e.command())).append('"')
              .append(",\"timestamp\":").append(e.timestamp());
            if (e.input1() != null) sb.append(",\"input1\":\"@").append(e.input1()).append('"');
            if (e.input2() != null) sb.append(",\"input2\":\"@").append(e.input2()).append('"');
            sb.append("}\n");
        }
        return sb.toString();
    }

    private String handleCallThat(String cmd) throws IOException, SQLException {
        String name = cmd.split("\\s+")[2];
        if (that instanceof VoidAnswer) return "{\"error\":\"THAT is empty\"}\n";

        int histId = thatHistId;

        // Persist in-memory BitSetAnswer to disk before binding a name to it
        if (that instanceof BitSetAnswer bsa) {
            var entry = history.findById(histId);
            if (entry.isPresent() && entry.get().bitsetFile() == null) {
                saveBitSetToFile(histId, bsa.bits());
            }
        }

        var displaced = names.bind(name, histId);
        int callId    = history.record(cmd, System.currentTimeMillis());
        history.setInputs(callId, histId, displaced.orElse(null));
        return "{\"bound\":\"" + escJson(name) + "\",\"historyId\":" + histId + "}\n";
    }

    private String handleCallById(String cmd) throws SQLException {
        String[] parts = cmd.split("\\s+");
        String ref = parts[1];
        if (!ref.startsWith("@"))
            throw new IllegalArgumentException("History reference must start with @ (e.g. CALL @42 name)");
        int targetId = Integer.parseInt(ref.substring(1));
        String name    = parts[2];
        var displaced  = names.bind(name, targetId);
        int callId     = history.record(cmd, System.currentTimeMillis());
        history.setInputs(callId, targetId, displaced.orElse(null));
        return "{\"bound\":\"" + escJson(name) + "\",\"historyId\":" + targetId + "}\n";
    }

    private String handleForget(String cmd) throws SQLException {
        String name = cmd.split("\\s+")[1];
        // Record the old binding in input1 so UNDO can restore it
        Integer oldHistId = names.resolve(name).orElse(null);
        names.forget(name);
        int forgetId = history.record(cmd, System.currentTimeMillis());
        if (oldHistId != null) history.setInputs(forgetId, oldHistId, null);
        return "{\"forgot\":\"" + escJson(name) + "\"}\n";
    }

    private String handleRecall(int histId) throws IOException, SQLException {
        var entry = history.findById(histId);
        if (entry.isEmpty()) return "{\"error\":\"No history entry @" + histId + "\"}\n";
        if (entry.get().sqlTable() != null)   return sql.selectAsJsonl(entry.get().sqlTable());
        if (entry.get().bitsetFile() != null) return displayBitSet(loadBitSetFromFile(entry.get().bitsetFile()));
        return handleQuery(entry.get().command());
    }

    private String handleUndo() throws SQLException {
        var entry = history.lastUndoable();
        if (entry.isEmpty()) return "{\"error\":\"nothing to undo\"}\n";

        String cmd   = entry.get().command();
        String upper = cmd.stripLeading().toUpperCase();
        int undoId   = history.record("UNDO", System.currentTimeMillis());

        if (upper.startsWith("CALL ")) {
            String name = cmd.strip().split("\\s+")[2]; // CALL THAT <name> or CALL @id <name>
            names.forget(name);
            Integer displaced = entry.get().input2();
            if (displaced != null) names.bind(name, displaced);
            history.setInputs(undoId, entry.get().id(), null);
            String msg = displaced != null
                ? "{\"undone\":\"CALL\",\"name\":\"" + escJson(name) + "\",\"restored\":" + displaced + "}"
                : "{\"undone\":\"CALL\",\"name\":\"" + escJson(name) + "\"}";
            return msg + "\n";
        }

        if (upper.startsWith("FORGET ")) {
            String name = cmd.strip().split("\\s+")[1];
            Integer oldHistId = entry.get().input1();
            if (oldHistId == null) return "{\"error\":\"cannot undo FORGET: old binding not recorded\"}\n";
            names.bind(name, oldHistId);
            history.setInputs(undoId, entry.get().id(), null);
            return "{\"undone\":\"FORGET\",\"name\":\"" + escJson(name) + "\",\"restored\":" + oldHistId + "}\n";
        }

        return "{\"error\":\"last undoable command was not a CALL or FORGET\"}\n";
    }

    private String handleSql(String cmd) {
        int histId = history.record(cmd, System.currentTimeMillis());
        try {
            TableAnswer answer = sql.execute(cmd);
            history.setSqlTable(histId, answer.sqlTableName());
            that = answer;
            thatHistId = histId;
            return "{\"sqlTable\":\"" + escJson(answer.sqlTableName())
                + "\",\"rowCount\":" + answer.rowCount() + "}\n";
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}\n";
        }
    }

    private String handleQuery(String cmd) throws IOException, SQLException {
        DslParser.Query parsed;
        try {
            parsed = DslParser.parse(cmd);
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}\n";
        }

        int histId = history.record(cmd, System.currentTimeMillis());

        return switch (parsed) {
            case DslParser.Pipeline p -> executePipeline(p, histId);
            case DslParser.AllSource q -> {
                long[] bits = QueryEngine.buildBitSet(heap, registry, q.className());
                that = new BitSetAnswer(bits, heap.objectCount());
                thatHistId = histId;
                // bitset_file is null until CALL THAT names it; display top N
                yield displayBitSet(bits);
            }
            case DslParser.AllTopByRetainedSize q -> {
                List<TopNRow> rows =
                    QueryEngine.allTopByRetainedSize(heap, registry, q.className(), q.n());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AllBottomByRetainedSize q -> {
                List<TopNRow> rows =
                    QueryEngine.allBottomByRetainedSize(heap, registry, q.className(), q.n());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AllRetaining q -> {
                List<TopNRow> rows =
                    QueryEngine.allRetaining(heap, registry, q.className(), q.op(), q.size());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AggregateCount q -> {
                long count = QueryEngine.aggregateCount(heap, registry, q.className());
                yield JsonlFormatter.formatCount(q.className(), count);
            }
            case DslParser.AggregateRetainedSize q -> {
                long value = QueryEngine.aggregateRetainedSize(
                    heap, registry, q.className(), q.func());
                yield JsonlFormatter.formatAggregateRetainedSize(q.className(), q.func(), value);
            }
            case DslParser.ClassesQuery q -> {
                var classes = QueryEngine.classes(heap, registry, q.glob());
                yield JsonlFormatter.formatClasses(classes);
            }
            case DslParser.ExplainQuery q -> {
                var path = QueryEngine.explain(heap, registry, q.denseId());
                yield JsonlFormatter.formatExplain(path);
            }
            case DslParser.DominatorSubtree q -> {
                List<TopNRow> rows =
                    QueryEngine.dominatorSubtree(heap, registry, q.denseId(), q.topN());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.StatusQuery ignored -> {
                yield "{\"objectCount\":" + heap.objectCount()
                    + ",\"classCount\":" + heap.classCount()
                    + "}\n";
            }
        };
    }

    // ── Pipeline execution helpers ────────────────────────────────────────────

    private long[] resolveSource(DslParser.Source source) throws IOException, SQLException {
        return switch (source) {
            case DslParser.ClassSource cs -> QueryEngine.buildBitSet(heap, registry, cs.className());
            case DslParser.NameSource ns  -> resolveBitSetByName(ns.name());
            case DslParser.ThatSource ignored -> {
                if (!(that instanceof BitSetAnswer bsa))
                    throw new IllegalArgumentException(
                        "THAT is not a bitset — run ALL <class> or FROM <name> first");
                yield bsa.bits().clone();
            }
        };
    }

    private long[] resolveBitSetByName(String name) throws IOException, SQLException {
        // Built-in names resolve without session state
        long[] builtin = QueryEngine.buildBuiltinBitSet(heap, registry, name);
        if (builtin != null) return builtin;

        int histId = names.resolve(name)
            .orElseThrow(() -> new IllegalArgumentException("Unknown name: '" + name + "'"));
        var entry = history.findById(histId)
            .orElseThrow(() -> new IllegalStateException("History @" + histId + " not found"));
        if (entry.bitsetFile() != null) return loadBitSetFromFile(entry.bitsetFile());
        throw new IllegalArgumentException(
            "'" + name + "' is a table result, not a bitset — use SQL SELECT to query it");
    }

    private long[] applyFilter(long[] bits, DslParser.Filter filter)
            throws IOException, SQLException {
        return switch (filter) {
            case DslParser.InFilter f -> {
                long[] other = resolveBitSetByName(f.name());
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, other.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & other[i];
                yield result;
            }
            case DslParser.NotInFilter f -> {
                long[] other = resolveBitSetByName(f.name());
                long[] result = bits.clone();
                int len = Math.min(bits.length, other.length);
                for (int i = 0; i < len; i++) result[i] &= ~other[i];
                yield result;
            }
            case DslParser.RetainedByFilter f -> {
                long[] retainerBits = resolveBitSetByName(f.name());
                long[] retained = QueryEngine.buildRetainedByBitSet(heap, registry, retainerBits);
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, retained.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & retained[i];
                yield result;
            }
            case DslParser.RetainingFilter f -> {
                int objectCount = heap.objectCount();
                long[] result = new long[bits.length];
                try (var retainedSize = registry.openRetainedSize()) {
                    for (int v = 0; v < objectCount; v++) {
                        if ((bits[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                            long rs = retainedSize.readLong(v);
                            if (matchesOp(rs, f.op(), f.size()))
                                result[v >>> 6] |= 1L << (v & 63);
                        }
                    }
                }
                yield result;
            }
            case DslParser.OfTypeFilter f -> {
                long[] typeBits = QueryEngine.buildOfTypeBitSet(heap, registry, f.className(), f.exactly());
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, typeBits.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & typeBits[i];
                yield result;
            }
            case DslParser.SizedFilter f -> {
                int objectCount = heap.objectCount();
                long[] result = new long[bits.length];
                try (var shallowSize = registry.openShallowSize()) {
                    for (int v = 0; v < objectCount; v++) {
                        if ((bits[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                            long ss = (long) shallowSize.readInt(v) * 8L;
                            if (matchesOp(ss, f.op(), f.size()))
                                result[v >>> 6] |= 1L << (v & 63);
                        }
                    }
                }
                yield result;
            }
            case DslParser.ReferencingFilter f -> {
                long[] targetBits = resolveBitSetByName(f.name());
                long[] refing = QueryEngine.buildReferencingBitSet(heap, registry, targetBits);
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, refing.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & refing[i];
                yield result;
            }
            case DslParser.ReferencedByFilter f -> {
                long[] sourceBits = resolveBitSetByName(f.name());
                long[] refdBy = QueryEngine.buildReferencedByBitSet(heap, registry, sourceBits);
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, refdBy.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & refdBy[i];
                yield result;
            }
            case DslParser.ReachableFromFilter f -> {
                long[] seedBits = resolveBitSetByName(f.name());
                long[] reachable = QueryEngine.buildReachableFromBitSet(heap, registry, seedBits);
                long[] result = new long[bits.length];
                int len = Math.min(bits.length, reachable.length);
                for (int i = 0; i < len; i++) result[i] = bits[i] & reachable[i];
                yield result;
            }
        };
    }

    private static boolean matchesOp(long value, String op, long threshold) {
        return switch (op) {
            case ">"  -> value >  threshold;
            case ">=" -> value >= threshold;
            case "<"  -> value <  threshold;
            case "<=" -> value <= threshold;
            case "="  -> value == threshold;
            default   -> throw new IllegalArgumentException("Unknown op: " + op);
        };
    }

    private String executePipeline(DslParser.Pipeline p, int histId)
            throws IOException, SQLException {
        long[] bits = resolveSource(p.source());
        for (var filter : p.filters()) bits = applyFilter(bits, filter);

        if (p.terminal() == null) {
            that = new BitSetAnswer(bits, heap.objectCount());
            thatHistId = histId;
            return displayBitSet(bits);
        }
        return switch (p.terminal()) {
            case DslParser.TopNTerminal t -> {
                var rows     = QueryEngine.topNFromBitSet(heap, registry, bits, t.n());
                String table = tables.writeTopNResult(rows);
                history.setSqlTable(histId, table);
                that = new TableAnswer(table, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.BottomNTerminal t -> {
                var rows     = QueryEngine.bottomNFromBitSet(heap, registry, bits, t.n());
                String table = tables.writeTopNResult(rows);
                history.setSqlTable(histId, table);
                that = new TableAnswer(table, rows.size());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AggregateCountTerminal ignored -> {
                long count = QueryEngine.bitSetCardinality(bits);
                yield "{\"count\":" + count + "}\n";
            }
            case DslParser.AggregateRetainedSizeTerminal t -> {
                long value = QueryEngine.aggregateFromBitSet(heap, registry, bits, t.func());
                yield JsonlFormatter.formatAggregateRetainedSize("(pipeline)", t.func(), value);
            }
        };
    }

    // ── Bitset helpers ────────────────────────────────────────────────────────

    private String displayBitSet(long[] bits) throws IOException {
        var rows = QueryEngine.topNFromBitSet(heap, registry, bits, IMPLICIT_DISPLAY_N);
        return JsonlFormatter.formatTopN(rows);
    }

    private void saveBitSetToFile(int histId, long[] bits) throws IOException {
        Path dir = heap.bitsetsDir();
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + ".bin";
        ByteBuffer buf = ByteBuffer.allocate(bits.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long word : bits) buf.putLong(word);
        Files.write(dir.resolve(filename), buf.array());
        history.setBitsetFile(histId, filename);
    }

    private long[] loadBitSetFromFile(String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(heap.bitsetsDir().resolve(filename));
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] bits = new long[bytes.length / 8];
        for (int i = 0; i < bits.length; i++) bits[i] = buf.getLong();
        return bits;
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
