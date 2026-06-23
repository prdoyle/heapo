package heapo.query_engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent DSL parser.
 *
 * <p>Returns a {@link ParseResult}: either {@link Invalid} (first error), {@link Incomplete}
 * (valid prefix, needs more input), or {@link Complete} (runnable query plus optional
 * completions for what could legally follow).
 *
 * <p>Tokenisation is trim-and-split on whitespace; no further regex processing is needed.
 */
public final class DslParser {

    // ── ParseResult ───────────────────────────────────────────────────────────

    public sealed interface ParseResult {}

    /** Input does not match the grammar — {@code message} explains why. */
    public record Invalid(String message) implements ParseResult {}

    /** Input is a valid prefix but not yet a complete query. */
    public record Incomplete(List<String> completions) implements ParseResult {}

    /** Input is a complete, executable query. {@code completions} lists what could legally follow. */
    public record Complete(Query action, List<String> completions) implements ParseResult {}

    // ── Completion markers ────────────────────────────────────────────────────

    /** The next token should be a fully-qualified class name (or {@code *}). */
    public static final String COMPLETE_CLASS    = "<class>";
    /** The next token should be a named bitset or built-in name. */
    public static final String COMPLETE_BITSET   = "<bitset>";
    /** The next token should be a new user-chosen name for binding. */
    public static final String COMPLETE_NEW_NAME = "<new-name>";
    /** The next token is a free-form identifier (e.g. field name). */
    public static final String COMPLETE_IDENT    = "<ident>";

    // ── Pipeline building blocks ──────────────────────────────────────────────

    public sealed interface Source {}
    public record ClassSource(String className) implements Source {}
    public record NameSource(String name)       implements Source {}
    public record ThatSource()                  implements Source {}

    public sealed interface Filter {}
    public record InFilter(String name)                           implements Filter {}
    public record NotInFilter(String name)                        implements Filter {}
    public record RetainedByFilter(String name)                   implements Filter {}
    public record RetainingFilter(String op, long size)           implements Filter {}
    public record OfTypeFilter(String className, boolean exactly) implements Filter {}
    public record SizedFilter(String op, long size)               implements Filter {}
    public record ReferencingFilter(String name)                  implements Filter {}
    public record ReferencedByFilter(String name)                 implements Filter {}
    public record ReachableFromFilter(String name)                implements Filter {}
    public record WhereFilter(String field, String op, String rawValue) implements Filter {}

    public sealed interface Terminal {}
    public record TopNTerminal(int n)                          implements Terminal {}
    public record BottomNTerminal(int n)                       implements Terminal {}
    public record AggregateCountTerminal()                     implements Terminal {}
    public record AggregateRetainedSizeTerminal(String func)   implements Terminal {}

    // ── Query types ───────────────────────────────────────────────────────────

    public sealed interface Query {}

    // DSL pipeline — covers all source→filter*→terminal forms
    public record Pipeline(Source source, List<Filter> filters, Terminal terminal) implements Query {}

    // Standalone DSL queries
    public record StatusQuery()                              implements Query {}
    public record ClassesQuery(String glob)                  implements Query {}  // null = all
    public record ExplainQuery(int denseId)                  implements Query {}

    // Session commands
    public record NamesQuery(String glob)                    implements Query {}  // null = all
    public record ExplainNameQuery(String name)              implements Query {}
    public record ThatQuery()                                implements Query {}
    public record UndoQuery()                                implements Query {}
    public record HistoryRecallQuery(int histId)             implements Query {}
    public record HistoryQuery(int limit)                    implements Query {}
    public record CallThatQuery(String name)                 implements Query {}
    public record CallByIdQuery(int histId, String name)     implements Query {}
    public record ForgetQuery(String name)                   implements Query {}

    // ── Grammar constants ─────────────────────────────────────────────────────

    private static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "=");

    private static final List<String> TOP_LEVEL = List.of(
        "ALL", "CLASS", "THAT",
        "GcRoots", "Threads", "ClassLoaders", "SoftReferences", "WeakReferences", "PhantomReferences",
        "STATUS", "CLASSES", "NAMES", "EXPLAIN",
        "HISTORY", "CALL", "FORGET", "UNDO"
    );

    private static final List<String> FILTER_KEYWORDS = List.of(
        "IN", "NOT", "RETAINED", "RETAINING", "OF", "SIZED",
        "REFERENCING", "REFERENCED", "REACHABLE", "WHERE"
    );

    private static final List<String> TERMINAL_KEYWORDS = List.of(
        "TOP", "BOTTOM", "COUNT", "SUM", "MAX"
    );

    private static final List<String> FILTER_OR_TERMINAL;
    static {
        var list = new ArrayList<>(FILTER_KEYWORDS);
        list.addAll(TERMINAL_KEYWORDS);
        FILTER_OR_TERMINAL = List.copyOf(list);
    }

    private DslParser() {}

    // ── Entry point ───────────────────────────────────────────────────────────

    public static ParseResult parse(String input) {
        String trimmed = input.strip();
        if (trimmed.isEmpty()) return new Incomplete(TOP_LEVEL);
        String[] t = trimmed.split("\\s+");
        return parseTop(t);
    }

    // ── Top-level dispatch ────────────────────────────────────────────────────

    private static ParseResult parseTop(String[] t) {
        return switch (t[0].toUpperCase()) {
            case "STATUS"  -> exactly1(t, new StatusQuery());
            case "UNDO"    -> exactly1(t, new UndoQuery());
            case "CLASS"   -> parseClassPipeline(t, 1);
            case "ALL"     -> parsePipeline(new NameSource("All"), t, 1);
            case "THAT"    -> t.length == 1
                ? complete(new ThatQuery(), FILTER_OR_TERMINAL)
                : parsePipeline(new ThatSource(), t, 1);
            case "CLASSES" -> parseClasses(t, 1);
            case "NAMES"   -> parseNames(t, 1);
            case "EXPLAIN" -> parseExplain(t, 1);
            case "HISTORY" -> parseHistory(t, 1);
            case "CALL"    -> parseCall(t, 1);
            case "FORGET"  -> parseForget(t, 1);
            default -> {
                if (isHistRef(t[0])) {
                    // h<n> alone recalls history; h<n> with filters/display is a pipeline source.
                    if (t.length == 1)
                        yield complete(new HistoryRecallQuery(Integer.parseInt(t[0].substring(1))), List.of());
                    yield parsePipeline(new NameSource(t[0]), t, 1);
                }
                // Built-in names (Threads, GcRoots, …), i<n> singletons, s<n>/t<n> sigils,
                // and user-named results are all resolved at execution time via NameSource.
                yield parsePipeline(new NameSource(t[0]), t, 1);
            }
        };
    }

    // ── CLASS pipeline source ─────────────────────────────────────────────────

    private static ParseResult parseClassPipeline(String[] t, int i) {
        if (i >= t.length) return incomplete(List.of(COMPLETE_CLASS, "*"));
        return parsePipeline(new ClassSource(t[i]), t, i + 1);
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private static ParseResult parsePipeline(Source source, String[] t, int i) {
        var filters = new ArrayList<Filter>();

        while (i < t.length) {

            if (eq(t[i], "IN")) {
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new InFilter(t[i++]));

            } else if (eq(t[i], "NOT")) {
                i++;
                if (i >= t.length) return incomplete(List.of("IN"));
                if (!eq(t[i], "IN")) return invalid("Expected IN after NOT, got: " + t[i]);
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new NotInFilter(t[i++]));

            } else if (eq(t[i], "RETAINED")) {
                i++;
                if (i >= t.length) return incomplete(List.of("BY"));
                if (!eq(t[i], "BY")) return invalid("Expected BY after RETAINED, got: " + t[i]);
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new RetainedByFilter(t[i++]));

            } else if (eq(t[i], "RETAINING")) {
                i++;
                if (i >= t.length) return incomplete(opList());
                if (!isOp(t[i])) return invalid("Expected comparison op after RETAINING, got: " + t[i]);
                String op = t[i++];
                if (i >= t.length) return incomplete(List.of("<bytes>"));
                long size = parseLong(t[i], "RETAINING " + op);
                if (size == Long.MIN_VALUE) return invalid("Expected number after RETAINING " + op + ", got: " + t[i]);
                filters.add(new RetainingFilter(op, size));
                i++;

            } else if (eq(t[i], "OF")) {
                i++;
                if (i >= t.length) return incomplete(List.of("TYPE"));
                if (!eq(t[i], "TYPE")) return invalid("Expected TYPE after OF, got: " + t[i]);
                i++;
                if (i >= t.length) return incomplete(List.of("EXACTLY", COMPLETE_CLASS));
                boolean exactly = eq(t[i], "EXACTLY");
                if (exactly) {
                    i++;
                    if (i >= t.length) return incomplete(List.of(COMPLETE_CLASS));
                }
                filters.add(new OfTypeFilter(t[i++], exactly));

            } else if (eq(t[i], "SIZED")) {
                i++;
                if (i >= t.length) return incomplete(opList());
                if (!isOp(t[i])) return invalid("Expected comparison op after SIZED, got: " + t[i]);
                String op = t[i++];
                if (i >= t.length) return incomplete(List.of("<bytes>"));
                long size = parseLong(t[i], "SIZED " + op);
                if (size == Long.MIN_VALUE) return invalid("Expected number after SIZED " + op + ", got: " + t[i]);
                filters.add(new SizedFilter(op, size));
                i++;

            } else if (eq(t[i], "REFERENCING")) {
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new ReferencingFilter(t[i++]));

            } else if (eq(t[i], "REFERENCED")) {
                i++;
                if (i >= t.length) return incomplete(List.of("BY"));
                if (!eq(t[i], "BY")) return invalid("Expected BY after REFERENCED, got: " + t[i]);
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new ReferencedByFilter(t[i++]));

            } else if (eq(t[i], "REACHABLE")) {
                i++;
                if (i >= t.length) return incomplete(List.of("FROM"));
                if (!eq(t[i], "FROM")) return invalid("Expected FROM after REACHABLE, got: " + t[i]);
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
                filters.add(new ReachableFromFilter(t[i++]));

            } else if (eq(t[i], "WHERE")) {
                i++;
                if (i >= t.length) return incomplete(List.of(COMPLETE_IDENT));
                String field = t[i++];
                if (i >= t.length) return incomplete(opList());
                if (!isOp(t[i])) return invalid("Expected comparison op after WHERE " + field + ", got: " + t[i]);
                String op = t[i++];
                if (i >= t.length) return incomplete(List.of("<value>"));
                filters.add(new WhereFilter(field, op, t[i++]));

            } else if (eq(t[i], "TOP") || eq(t[i], "BOTTOM")) {
                String kw = t[i].toUpperCase();
                return parseTerminalSuffix(kw, source, List.copyOf(filters), t, i + 1);

            } else if (eq(t[i], "COUNT")) {
                if (i + 1 < t.length) return invalid("Unexpected tokens after COUNT");
                return complete(new Pipeline(source, List.copyOf(filters), new AggregateCountTerminal()), List.of());

            } else if (eq(t[i], "SUM")) {
                i++;
                if (i >= t.length) return incomplete(List.of("retainedSize"));
                if (!eq(t[i], "retainedSize")) return invalid("Expected retainedSize after SUM, got: " + t[i]);
                if (i + 1 < t.length) return invalid("Unexpected tokens after SUM retainedSize");
                return complete(new Pipeline(source, List.copyOf(filters), new AggregateRetainedSizeTerminal("SUM")), List.of());

            } else if (eq(t[i], "MAX")) {
                i++;
                if (i >= t.length) return incomplete(List.of("retainedSize"));
                if (!eq(t[i], "retainedSize")) return invalid("Expected retainedSize after MAX, got: " + t[i]);
                if (i + 1 < t.length) return invalid("Unexpected tokens after MAX retainedSize");
                return complete(new Pipeline(source, List.copyOf(filters), new AggregateRetainedSizeTerminal("MAX")), List.of());

            } else {
                return invalid("Expected filter or terminal keyword, got: " + t[i]);
            }
        }

        return complete(new Pipeline(source, List.copyOf(filters), null), FILTER_OR_TERMINAL);
    }

    /** Parse the {@code <n> [BY retainedSize]} suffix of TOP/BOTTOM, then wrap in a Pipeline. */
    private static ParseResult parseTerminalSuffix(String kw, Source source, List<Filter> filters,
                                                    String[] t, int i) {
        if (i >= t.length) return incomplete(List.of("<n>"));
        int n = parseInt(t[i]);
        if (n < 0) return invalid("Expected positive integer after " + kw + ", got: " + t[i]);
        i++;
        Terminal terminal = kw.equals("TOP") ? new TopNTerminal(n) : new BottomNTerminal(n);

        if (i >= t.length) return complete(new Pipeline(source, filters, terminal), List.of("BY"));
        if (eq(t[i], "BY")) {
            i++;
            if (i >= t.length) return incomplete(List.of("retainedSize"));
            if (!eq(t[i], "retainedSize"))
                return invalid("Expected retainedSize after BY, got: " + t[i]);
            i++;
        }
        if (i < t.length) return invalid("Unexpected tokens after terminal: " + t[i]);
        return complete(new Pipeline(source, filters, terminal), List.of());
    }

    // ── Standalone queries ────────────────────────────────────────────────────

    private static ParseResult parseClasses(String[] t, int i) {
        if (i >= t.length) return complete(new ClassesQuery(null), List.of(COMPLETE_CLASS));
        String glob = t[i++];
        if (i < t.length) return invalid("Unexpected tokens after CLASSES " + glob);
        return complete(new ClassesQuery(glob), List.of());
    }

    private static ParseResult parseNames(String[] t, int i) {
        if (i >= t.length) return complete(new NamesQuery(null), List.of("<glob>"));
        String glob = t[i++];
        if (i < t.length) return invalid("Unexpected tokens after NAMES " + glob);
        return complete(new NamesQuery(glob), List.of());
    }

    private static ParseResult parseExplain(String[] t, int i) {
        if (i >= t.length) return incomplete(List.of("i<n>", COMPLETE_BITSET));
        String arg = t[i];
        if (isObjRef(arg)) {
            int denseId = Integer.parseInt(arg.substring(1));
            if (i + 1 < t.length) return invalid("Unexpected tokens after EXPLAIN " + arg);
            return complete(new ExplainQuery(denseId), List.of());
        }
        if (i + 1 < t.length) return invalid("Unexpected tokens after EXPLAIN " + arg);
        return complete(new ExplainNameQuery(arg), List.of());
    }

    // ── Session commands ──────────────────────────────────────────────────────

    private static ParseResult parseHistory(String[] t, int i) {
        if (i >= t.length) return complete(new HistoryQuery(10), List.of("<n>"));
        int n = parseInt(t[i]);
        if (n < 0) return invalid("Expected positive integer after HISTORY, got: " + t[i]);
        if (i + 1 < t.length) return invalid("Unexpected tokens after HISTORY " + n);
        return complete(new HistoryQuery(n), List.of());
    }

    private static ParseResult parseCall(String[] t, int i) {
        if (i >= t.length) return incomplete(List.of("THAT", "h<n>"));
        if (eq(t[i], "THAT")) {
            i++;
            if (i >= t.length) return incomplete(List.of(COMPLETE_NEW_NAME));
            String name = t[i++];
            if (i < t.length) return invalid("Unexpected tokens after CALL THAT " + name);
            return complete(new CallThatQuery(name), List.of());
        }
        if (isHistRef(t[i])) {
            int histId = Integer.parseInt(t[i].substring(1));
            i++;
            if (i >= t.length) return incomplete(List.of(COMPLETE_NEW_NAME));
            String name = t[i++];
            if (i < t.length) return invalid("Unexpected tokens after CALL h" + histId + " " + name);
            return complete(new CallByIdQuery(histId, name), List.of());
        }
        return invalid("Expected THAT or h<n> after CALL, got: " + t[i]);
    }

    private static ParseResult parseForget(String[] t, int i) {
        if (i >= t.length) return incomplete(List.of(COMPLETE_BITSET));
        String name = t[i++];
        if (i < t.length) return invalid("Unexpected tokens after FORGET " + name);
        return complete(new ForgetQuery(name), List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ParseResult exactly1(String[] t, Query q) {
        if (t.length > 1) return invalid("'" + t[0].toUpperCase() + "' takes no arguments");
        return complete(q, List.of());
    }

    private static Complete complete(Query q, List<String> completions) {
        return new Complete(q, completions);
    }

    private static Incomplete incomplete(List<String> completions) {
        return new Incomplete(completions);
    }

    private static Invalid invalid(String msg) {
        return new Invalid(msg);
    }

    private static boolean eq(String token, String keyword) {
        return token.equalsIgnoreCase(keyword);
    }

    private static boolean isOp(String s) {
        return OPS.contains(s);
    }

    private static List<String> opList() {
        return List.of(">", ">=", "<", "<=", "=");
    }

    /** Returns true if {@code s} is {@code h} followed by one or more digits. */
    static boolean isHistRef(String s) {
        if (s.length() < 2 || Character.toUpperCase(s.charAt(0)) != 'H') return false;
        for (int i = 1; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Returns true if {@code s} is {@code i} followed by one or more digits. */
    public static boolean isObjRef(String s) {
        if (s.length() < 2 || Character.toUpperCase(s.charAt(0)) != 'I') return false;
        for (int i = 1; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Parses a non-negative integer, or returns -1 on failure. */
    private static int parseInt(String s) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses a long, or returns {@code Long.MIN_VALUE} on failure. */
    private static long parseLong(String s, String ctx) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
