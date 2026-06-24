package heapo.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.BitSet;
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
    private int thatHistId = -1;

    private static final int IMPLICIT_DISPLAY_N = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String toJsonLine(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new AssertionError("unreachable", e);
        }
    }

    private static String errorJson(String message) {
        return toJsonLine(MAPPER.createObjectNode().put("error", message));
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    /**
     * Execute a command line. Returns JSONL output (may be empty for void commands).
     */
    public String execute(String command) throws IOException, SQLException {
        String trimmed = command.strip();
        if (trimmed.isEmpty()) return "";

        if (SqlRouter.isSql(trimmed)) return handleSql(trimmed);

        return switch (DslParser.parse(trimmed)) {
            case DslParser.Invalid e ->
                errorJson(e.message());
            case DslParser.Incomplete i ->
                errorJson("Incomplete command; expected: " + String.join(", ", i.completions()));
            case DslParser.Complete c ->
                handleComplete(c.action(), trimmed);
        };
    }

    public Answer getThat()         { return that; }
    public NamesManager names()     { return names; }
    public void clearSession()      { db.truncateSession(); }

    /** Returns the typed result sigil for the prompt, e.g. {@code "t17"} or {@code "s42"}, or empty. */
    public String thatSigil() {
        if (thatHistId < 0) return "";
        return switch (that) {
            case BitSetAnswer ignored -> "s" + thatHistId;
            case TableAnswer  ignored -> "t" + thatHistId;
            default                  -> "";
        };
    }

    // ── Query dispatch ────────────────────────────────────────────────────────

    private String handleComplete(DslParser.Query q, String rawCmd)
            throws IOException, SQLException {
        return switch (q) {
            // Read-only session commands — not recorded in history
            case DslParser.NamesQuery nq         -> handleNames(nq.glob());
            case DslParser.ThatQuery ignored     -> handleThat();
            case DslParser.HistoryQuery hq       -> handleHistory(hq.limit());
            case DslParser.HistoryRecallQuery hr -> handleRecall(hr.histId());
            case DslParser.ExplainNameQuery en   -> handleExplainName(en.name());

            // Read-only DSL queries — recorded in history, no THAT side-effect
            case DslParser.StatusQuery ignored -> {
                history.record(rawCmd, System.currentTimeMillis());
                // objectCount includes null sentinel at dense ID 0; display real count
                yield toJsonLine(MAPPER.createObjectNode()
                    .put("objectCount", heap.objectCount() - 1)
                    .put("classCount", heap.classCount()));
            }
            case DslParser.ReadQuery rq -> {
                history.record(rawCmd, System.currentTimeMillis());
                String content = QueryEngine.readFull(heap, registry, rq.denseId());
                yield JsonlFormatter.formatRead(rq.denseId(), content);
            }
            case DslParser.ClassesQuery cq -> {
                history.record(rawCmd, System.currentTimeMillis());
                yield JsonlFormatter.formatClasses(QueryEngine.classes(heap, registry, cq.glob()));
            }
            case DslParser.ExplainQuery eq -> {
                history.record(rawCmd, System.currentTimeMillis());
                yield JsonlFormatter.formatExplain(QueryEngine.explain(heap, registry, eq.denseId()));
            }
            case DslParser.InspectQuery iq -> {
                history.record(rawCmd, System.currentTimeMillis());
                yield JsonlFormatter.formatInspect(QueryEngine.inspect(heap, registry, iq.denseId()));
            }

            // Mutating session commands — record themselves internally
            case DslParser.UndoQuery ignored        -> handleUndo();
            case DslParser.CallThatQuery ct         -> handleCallThat(ct.name(), rawCmd);
            case DslParser.CallByIdQuery cb         -> handleCallById(cb.histId(), cb.name(), rawCmd);
            case DslParser.ForgetQuery fq           -> handleForget(fq.name(), rawCmd);

            // DSL pipeline — recorded before execution, updates THAT
            case DslParser.Pipeline p -> {
                int histId = history.record(rawCmd, System.currentTimeMillis());
                yield executePipeline(p, histId);
            }
        };
    }

    // ── Session command handlers ──────────────────────────────────────────────

    private String handleNames(String glob) {
        var all = names.all();
        var sb = new StringBuilder();
        for (var e : all.entrySet()) {
            if (glob == null || matchGlob(e.getKey(), glob)) {
                sb.append(toJsonLine(MAPPER.createObjectNode()
                    .put("name", e.getKey())
                    .put("historyId", e.getValue())));
            }
        }
        if (sb.isEmpty()) {
            var node = MAPPER.createObjectNode();
            node.putArray("names");
            return toJsonLine(node);
        }
        return sb.toString();
    }

    private String handleExplainName(String name) throws SQLException {
        int histId = parseHistId(name);
        if (histId < 0) {
            var histIdOpt = names.resolve(name);
            if (histIdOpt.isEmpty())
                return errorJson("Unknown name: '" + name + "'");
            histId = histIdOpt.get();
        }
        var entryOpt = history.findById(histId);
        if (entryOpt.isEmpty())
            return errorJson("History h" + histId + " not found");
        var entry = entryOpt.get();
        String type = entry.bitsetFile() != null ? "bitset"
                    : entry.sqlTable() != null   ? "table"
                    : "other";
        var node = MAPPER.createObjectNode()
            .put("name", name)
            .put("histId", "h" + histId)
            .put("type", type)
            .put("command", entry.command());
        if (entry.input1() != null) node.put("derivedFrom", "h" + entry.input1());
        return toJsonLine(node);
    }

    private String handleThat() throws IOException {
        if (that instanceof VoidAnswer)    return errorJson("THAT is empty");
        if (that instanceof BitSetAnswer b) return displayBitSet(b.bits());
        if (that instanceof TableAnswer t)  return sql.selectAsJsonl(t.sqlTableName());
        return errorJson("cannot display THAT");
    }

    private String handleHistory(int limit) {
        var entries = history.recent(limit);
        var sb = new StringBuilder();
        for (var e : entries) {
            var node = MAPPER.createObjectNode()
                .put("id", "h" + e.id())
                .put("command", e.command())
                .put("timestamp", e.timestamp());
            if (e.input1() != null) node.put("input1", "h" + e.input1());
            if (e.input2() != null) node.put("input2", "h" + e.input2());
            sb.append(toJsonLine(node));
        }
        return sb.toString();
    }

    private String handleCallThat(String name, String rawCmd) throws IOException, SQLException {
        if (that instanceof VoidAnswer) return errorJson("THAT is empty");

        int histId = thatHistId;

        if (that instanceof BitSetAnswer bsa) {
            var entry = history.findById(histId);
            if (entry.isPresent() && entry.get().bitsetFile() == null) {
                saveBitSetToFile(histId, bsa.bits());
            }
        }

        var displaced = names.bind(name, histId);
        int callId    = history.record(rawCmd, System.currentTimeMillis());
        history.setInputs(callId, histId, displaced.orElse(null));
        return toJsonLine(MAPPER.createObjectNode()
            .put("bound", name)
            .put("historyId", histId));
    }

    private String handleCallById(int targetId, String name, String rawCmd) throws SQLException {
        var displaced = names.bind(name, targetId);
        int callId    = history.record(rawCmd, System.currentTimeMillis());
        history.setInputs(callId, targetId, displaced.orElse(null));
        return toJsonLine(MAPPER.createObjectNode()
            .put("bound", name)
            .put("historyId", targetId));
    }

    private String handleForget(String name, String rawCmd) throws SQLException {
        Integer oldHistId = names.resolve(name).orElse(null);
        names.forget(name);
        int forgetId = history.record(rawCmd, System.currentTimeMillis());
        if (oldHistId != null) history.setInputs(forgetId, oldHistId, null);
        return toJsonLine(MAPPER.createObjectNode().put("forgot", name));
    }

    private String handleRecall(int histId) throws IOException, SQLException {
        var entry = history.findById(histId);
        if (entry.isEmpty()) return errorJson("No history entry h" + histId);
        if (entry.get().sqlTable()   != null) return sql.selectAsJsonl(entry.get().sqlTable());
        if (entry.get().bitsetFile() != null) return displayBitSet(loadBitSetFromFile(entry.get().bitsetFile()));
        return handleComplete(DslParser.parse(entry.get().command()) instanceof DslParser.Complete c
            ? c.action() : null, entry.get().command());
    }

    private String handleUndo() throws SQLException {
        var entry = history.lastUndoable();
        if (entry.isEmpty()) return errorJson("nothing to undo");

        String cmd   = entry.get().command();
        int undoId   = history.record("UNDO", System.currentTimeMillis());

        if (DslParser.parse(cmd) instanceof DslParser.Complete c) {
            if (c.action() instanceof DslParser.CallThatQuery ct) {
                names.forget(ct.name());
                Integer displaced = entry.get().input2();
                if (displaced != null) names.bind(ct.name(), displaced);
                history.setInputs(undoId, entry.get().id(), null);
                var node = MAPPER.createObjectNode().put("undone", "CALL").put("name", ct.name());
                if (displaced != null) node.put("restored", displaced.intValue());
                return toJsonLine(node);
            }
            if (c.action() instanceof DslParser.CallByIdQuery cb) {
                names.forget(cb.name());
                Integer displaced = entry.get().input2();
                if (displaced != null) names.bind(cb.name(), displaced);
                history.setInputs(undoId, entry.get().id(), null);
                var node = MAPPER.createObjectNode().put("undone", "CALL").put("name", cb.name());
                if (displaced != null) node.put("restored", displaced.intValue());
                return toJsonLine(node);
            }
            if (c.action() instanceof DslParser.ForgetQuery fq) {
                Integer oldHistId = entry.get().input1();
                if (oldHistId == null) return errorJson("cannot undo FORGET: old binding not recorded");
                names.bind(fq.name(), oldHistId);
                history.setInputs(undoId, entry.get().id(), null);
                return toJsonLine(MAPPER.createObjectNode()
                    .put("undone", "FORGET")
                    .put("name", fq.name())
                    .put("restored", oldHistId.intValue()));
            }
        }

        return errorJson("last undoable command was not a CALL or FORGET");
    }

    private String handleSql(String cmd) {
        int histId = history.record(cmd, System.currentTimeMillis());
        try {
            TableAnswer answer = sql.execute(cmd);
            history.setSqlTable(histId, answer.sqlTableName());
            that = answer;
            thatHistId = histId;
            return toJsonLine(MAPPER.createObjectNode()
                .put("sqlTable", answer.sqlTableName())
                .put("rowCount", answer.rowCount()));
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    // ── Pipeline execution ────────────────────────────────────────────────────

    private BitSet resolveSource(DslParser.Source source) throws IOException, SQLException {
        return switch (source) {
            case DslParser.ClassSource cs -> QueryEngine.buildBitSet(heap, registry, cs.className());
            case DslParser.NameSource ns  -> resolveBitSetByName(ns.name());
            case DslParser.ThatSource ignored -> {
                if (!(that instanceof BitSetAnswer bsa))
                    throw new IllegalArgumentException(
                        "THAT is not a bitset — run CLASS <class> or FROM <name> first");
                yield (BitSet) bsa.bits().clone();
            }
        };
    }

    private BitSet resolveBitSetByName(String name) throws IOException, SQLException {
        if (DslParser.isObjRef(name)) {
            int denseId = Integer.parseInt(name.substring(1));
            BitSet bits = new BitSet(denseId + 1);
            bits.set(denseId);
            return bits;
        }

        if (name.equalsIgnoreCase("THAT")) {
            if (!(that instanceof BitSetAnswer bsa))
                throw new IllegalArgumentException("THAT is not a bitset");
            return (BitSet) bsa.bits().clone();
        }

        BitSet builtin = QueryEngine.buildBuiltinBitSet(heap, registry, name);
        if (builtin != null) return builtin;

        int histId = parseHistId(name);
        if (histId < 0) {
            histId = names.resolve(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown name: '" + name + "'"));
        }
        // Fast path: current in-memory result (bitset not yet persisted to disk)
        if (histId == thatHistId && that instanceof BitSetAnswer bsa) {
            return (BitSet) bsa.bits().clone();
        }
        var entry = history.findById(histId)
            .orElseThrow(() -> new IllegalArgumentException("No history entry " + name));
        if (entry.bitsetFile() != null) return loadBitSetFromFile(entry.bitsetFile());
        if (entry.sqlTable() != null) throw new IllegalArgumentException(
            name + " is a table result, not a bitset — query it with SQL SELECT");
        throw new IllegalArgumentException(
            name + " was not persisted. Use CALL THAT <name> to save it for later reference.");
    }

    /**
     * Parses a session sigil ({@code h<n>}, {@code s<n>}, {@code t<n>}) into a history ID.
     * Returns {@code -1} if {@code name} does not match any sigil pattern.
     */
    private static int parseHistId(String name) {
        if (name == null || name.length() < 2) return -1;
        char first = name.charAt(0);
        if (first != 'h' && first != 's' && first != 't') return -1;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return -1;
        }
        return Integer.parseInt(name.substring(1));
    }

    private BitSet applyWhereFilter(BitSet bits, DslParser.WhereFilter f, String contextClass)
            throws IOException {
        if (contextClass == null)
            throw new IllegalArgumentException(
                "WHERE requires a class context — use CLASS <class> or add OF TYPE <class> before WHERE");
        ClassNameIndex nameIndex = ClassNameIndex.load(heap);
        int classDenseId = nameIndex.resolve(contextClass);
        if (classDenseId < 0)
            throw new IllegalArgumentException("Unknown class: '" + contextClass + "'");
        long longValue = parseWhereValue(f.rawValue());
        return QueryEngine.buildWhereFilterBitSet(heap, registry, bits, classDenseId,
                                                   f.field(), f.op(), longValue);
    }

    private BitSet applyWhereStringFilter(BitSet bits, DslParser.WhereStringFilter f, String contextClass)
            throws IOException {
        if (contextClass == null)
            throw new IllegalArgumentException(
                "WHERE requires a class context — use CLASS <class> or add OF TYPE <class> before WHERE");
        ClassNameIndex nameIndex = ClassNameIndex.load(heap);
        int classDenseId = nameIndex.resolve(contextClass);
        if (classDenseId < 0)
            throw new IllegalArgumentException("Unknown class: '" + contextClass + "'");
        return QueryEngine.buildWhereStringFilterBitSet(heap, registry, bits, classDenseId,
                                                         f.field(), f.value(),
                                                         f.leadingStar(), f.trailingStar());
    }

    private static long parseWhereValue(String raw) {
        if (raw.equalsIgnoreCase("true"))  return 1L;
        if (raw.equalsIgnoreCase("false")) return 0L;
        return Long.parseLong(raw);
    }

    private BitSet applyFilter(BitSet bits, DslParser.Filter filter)
            throws IOException, SQLException {
        return switch (filter) {
            case DslParser.InFilter f -> {
                BitSet other = resolveBitSetByName(f.name());
                BitSet result = (BitSet) bits.clone();
                result.and(other);
                yield result;
            }
            case DslParser.NotInFilter f -> {
                BitSet other = resolveBitSetByName(f.name());
                BitSet result = (BitSet) bits.clone();
                result.andNot(other);
                yield result;
            }
            case DslParser.RetainedByFilter f -> {
                BitSet retainerBits = resolveBitSetByName(f.name());
                BitSet retained = QueryEngine.buildRetainedByBitSet(heap, registry, retainerBits);
                BitSet result = (BitSet) bits.clone();
                result.and(retained);
                yield result;
            }
            case DslParser.RetainingFilter f -> {
                BitSet result = new BitSet(heap.objectCount());
                try (var retainedSize = registry.openRetainedSize()) {
                    for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
                        long rs = retainedSize.readLong(v);
                        if (matchesOp(rs, f.op(), f.size())) result.set(v);
                    }
                }
                yield result;
            }
            case DslParser.OfTypeFilter f -> {
                BitSet typeBits = QueryEngine.buildOfTypeBitSet(heap, registry, f.className(), f.exactly());
                BitSet result = (BitSet) bits.clone();
                result.and(typeBits);
                yield result;
            }
            case DslParser.SizedFilter f -> {
                BitSet result = new BitSet(heap.objectCount());
                try (var shallowSize = registry.openShallowSize()) {
                    for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
                        long ss = (long) shallowSize.readInt(v) * 8L;
                        if (matchesOp(ss, f.op(), f.size())) result.set(v);
                    }
                }
                yield result;
            }
            case DslParser.ReferencingFilter f -> {
                BitSet targetBits = resolveBitSetByName(f.name());
                BitSet refing = QueryEngine.buildReferencingBitSet(heap, registry, targetBits);
                BitSet result = (BitSet) bits.clone();
                result.and(refing);
                yield result;
            }
            case DslParser.ReferencedByFilter f -> {
                BitSet sourceBits = resolveBitSetByName(f.name());
                BitSet refdBy = QueryEngine.buildReferencedByBitSet(heap, registry, sourceBits);
                BitSet result = (BitSet) bits.clone();
                result.and(refdBy);
                yield result;
            }
            case DslParser.ReachableFromFilter f -> {
                BitSet seedBits = resolveBitSetByName(f.name());
                BitSet reachable = QueryEngine.buildReachableFromBitSet(heap, registry, seedBits);
                BitSet result = (BitSet) bits.clone();
                result.and(reachable);
                yield result;
            }
            case DslParser.WhereFilter ignored ->
                throw new IllegalStateException("WhereFilter must be handled in executePipeline");
            case DslParser.WhereStringFilter ignored ->
                throw new IllegalStateException("WhereStringFilter must be handled in executePipeline");
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
        BitSet bits = resolveSource(p.source());

        String contextClass = switch (p.source()) {
            case DslParser.ClassSource cs -> cs.className().equals("*") ? null : cs.className();
            default -> null;
        };

        for (var filter : p.filters()) {
            if (filter instanceof DslParser.OfTypeFilter f) contextClass = f.className();
            if (filter instanceof DslParser.WhereFilter f) {
                bits = applyWhereFilter(bits, f, contextClass);
            } else if (filter instanceof DslParser.WhereStringFilter f) {
                bits = applyWhereStringFilter(bits, f, contextClass);
            } else {
                bits = applyFilter(bits, filter);
            }
        }

        if (p.terminal() == null) {
            that = new BitSetAnswer(bits, heap.objectCount());
            thatHistId = histId;
            return displayBitSet(bits);
        }
        return switch (p.terminal()) {
            case DslParser.TopNTerminal t -> {
                var rows = QueryEngine.topNFromBitSet(heap, registry, bits, t.n());
                var truncated = new BitSet(heap.objectCount());
                rows.forEach(r -> truncated.set(r.denseId()));
                that = new BitSetAnswer(truncated, heap.objectCount());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.ShowNTerminal t -> {
                that = new BitSetAnswer(bits, heap.objectCount());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, registry, bits, t.n()));
            }
            case DslParser.SampleNTerminal t -> {
                that = new BitSetAnswer(bits, heap.objectCount());
                thatHistId = histId;
                yield JsonlFormatter.formatTopN(QueryEngine.sampleFromBitSet(heap, registry, bits, t.n()));
            }
            case DslParser.AggregateCountTerminal ignored -> {
                long count = bits.cardinality();
                yield toJsonLine(MAPPER.createObjectNode().put("count", count));
            }
            case DslParser.AggregateRetainedSizeTerminal t -> {
                long value = QueryEngine.aggregateFromBitSet(heap, registry, bits, t.func());
                yield JsonlFormatter.formatAggregateRetainedSize("(pipeline)", t.func(), value);
            }
        };
    }

    // ── Bitset helpers ────────────────────────────────────────────────────────

    private String displayBitSet(BitSet bits) throws IOException {
        var rows = QueryEngine.topNFromBitSet(heap, registry, bits, IMPLICIT_DISPLAY_N);
        return JsonlFormatter.formatTopN(rows);
    }

    private void saveBitSetToFile(int histId, BitSet bits) throws IOException {
        Path dir = heap.bitsetsDir();
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + ".bin";
        long[] words = bits.toLongArray();
        ByteBuffer buf = ByteBuffer.allocate(words.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long word : words) buf.putLong(word);
        Files.write(dir.resolve(filename), buf.array());
        history.setBitsetFile(histId, filename);
    }

    private BitSet loadBitSetFromFile(String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(heap.bitsetsDir().resolve(filename));
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] words = new long[bytes.length / 8];
        buf.asLongBuffer().get(words);
        return BitSet.valueOf(words);
    }

    private static boolean matchGlob(String name, String glob) {
        return name.matches(glob.replace(".", "\\.").replace("*", ".*"));
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
