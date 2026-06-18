package heapo.cli;

import heapo.indexes.IndexRegistry;
import heapo.model.*;
import heapo.query_engine.*;
import heapo.session.*;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

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

    private Answer that = VoidAnswer.INSTANCE;

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
        if (trimmed.equalsIgnoreCase("NAMES")) return handleNames();
        if (trimmed.matches("(?i)HISTORY(\\s+\\d+)?")) return handleHistory(trimmed);
        if (trimmed.matches("(?i)CALL\\s+THAT\\s+\\S+")) return handleCallThat(trimmed);
        if (trimmed.matches("(?i)CALL\\s+#\\d+\\s+\\S+")) return handleCallById(trimmed);
        if (trimmed.matches("(?i)FORGET\\s+\\S+")) return handleForget(trimmed);

        // ── SQL query ─────────────────────────────────────────────────────────
        if (SqlRouter.isSql(trimmed)) return handleSql(trimmed);

        // ── DSL query ─────────────────────────────────────────────────────────
        return handleQuery(trimmed);
    }

    public Answer getThat() { return that; }

    // ── Session command handlers ──────────────────────────────────────────────

    private String handleNames() {
        var all = names.all();
        if (all.isEmpty()) return "{\"names\":[]}\n";
        var sb = new StringBuilder();
        for (var e : all.entrySet()) {
            sb.append("{\"name\":\"").append(escJson(e.getKey()))
              .append("\",\"historyId\":").append(e.getValue()).append("}\n");
        }
        return sb.toString();
    }

    private String handleHistory(String cmd) {
        int n = 10;
        String[] parts = cmd.split("\\s+");
        if (parts.length >= 2) n = Integer.parseInt(parts[1]);

        var entries = history.recent(n);
        var sb = new StringBuilder();
        for (var e : entries) {
            sb.append("{\"id\":").append(e.id())
              .append(",\"command\":\"").append(escJson(e.command())).append('"')
              .append(",\"timestamp\":").append(e.timestamp())
              .append("}\n");
        }
        return sb.toString();
    }

    private String handleCallThat(String cmd) throws SQLException {
        String name = cmd.split("\\s+")[2];
        if (that instanceof VoidAnswer) {
            return "{\"error\":\"THAT is empty\"}\n";
        }
        int histId = thatHistoryId();
        var displaced = names.bind(name, histId);

        // Record in history
        int callId = history.record(cmd, System.currentTimeMillis());
        history.setInputs(callId, histId, displaced.orElse(null));

        return "{\"bound\":\"" + escJson(name) + "\",\"historyId\":" + histId + "}\n";
    }

    private String handleCallById(String cmd) throws SQLException {
        String[] parts = cmd.split("\\s+");
        int targetId   = Integer.parseInt(parts[1].substring(1));
        String name    = parts[2];
        var displaced  = names.bind(name, targetId);
        int callId     = history.record(cmd, System.currentTimeMillis());
        history.setInputs(callId, targetId, displaced.orElse(null));
        return "{\"bound\":\"" + escJson(name) + "\",\"historyId\":" + targetId + "}\n";
    }

    private String handleForget(String cmd) {
        String name = cmd.split("\\s+")[1];
        names.forget(name);
        history.record(cmd, System.currentTimeMillis());
        return "{\"forgot\":\"" + escJson(name) + "\"}\n";
    }

    private String handleSql(String cmd) {
        int histId = history.record(cmd, System.currentTimeMillis());
        try {
            TableAnswer answer = sql.execute(cmd);
            history.setSqlTable(histId, answer.sqlTableName());
            that = answer;
            return "{\"sqlTable\":\"" + escJson(answer.sqlTableName())
                + "\",\"rowCount\":" + answer.rowCount() + "}\n";
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}\n";
        }
    }

    private String handleQuery(String cmd) throws IOException {
        DslParser.Query parsed;
        try {
            parsed = DslParser.parse(cmd);
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}\n";
        }

        int histId = history.record(cmd, System.currentTimeMillis());

        return switch (parsed) {
            case DslParser.AllTopByRetainedSize q -> {
                List<TopNRow> rows =
                    QueryEngine.allTopByRetainedSize(heap, registry, q.className(), q.n());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AllBottomByRetainedSize q -> {
                List<TopNRow> rows =
                    QueryEngine.allBottomByRetainedSize(heap, registry, q.className(), q.n());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.AllRetaining q -> {
                List<TopNRow> rows =
                    QueryEngine.allRetaining(heap, registry, q.className(), q.op(), q.size());
                String tableName = tables.writeTopNResult(rows);
                history.setSqlTable(histId, tableName);
                that = new TableAnswer(tableName, rows.size());
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
                yield JsonlFormatter.formatTopN(rows);
            }
            case DslParser.StatusQuery ignored -> {
                yield "{\"objectCount\":" + heap.objectCount()
                    + ",\"classCount\":" + heap.classCount()
                    + "}\n";
            }
        };
    }

    // THAT's history id: the most recent entry with a sql_table or bitset_file
    private int thatHistoryId() {
        return history.lastWithStorage()
            .map(HistoryManager.Entry::id)
            .orElseThrow(() -> new IllegalStateException("No stored result for THAT"));
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
