package heapo.query_engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DSL parser. Supported queries:
 * <ul>
 *   <li>{@code CLASS <class>} — bitset of all instances (pipeline source)
 *   <li>{@code CLASS <class> TOP <n> [BY retainedSize]}
 *   <li>{@code CLASS <class> BOTTOM <n> [BY retainedSize]}
 *   <li>{@code CLASS <class> RETAINING > <bytes>}
 *   <li>{@code CLASS <class> COUNT}
 *   <li>{@code CLASS <class> SUM retainedSize}
 *   <li>{@code CLASS <class> MAX retainedSize}
 *   <li>{@code FROM <name> | FROM THAT} — named/current bitset as source
 *   <li>{@code <source> [filter]* [terminal]} — composable pipeline
 *   <li>{@code CLASSES [MATCHING <glob>]}
 *   <li>{@code EXPLAIN &<denseId>}
 *   <li>{@code RETAINED BY &<id> [TOP n [BY retainedSize]]}
 *   <li>{@code STATUS}
 * </ul>
 * {@code <class>} is a fully-qualified dotted class name or {@code *} for all objects.
 */
public final class DslParser {

    // ── Pipeline building blocks ──────────────────────────────────────────────

    public sealed interface Source {}
    public record ClassSource(String className) implements Source {}
    public record NameSource(String name)       implements Source {}
    public record ThatSource()                  implements Source {}

    public sealed interface Filter {}
    public record InFilter(String name)         implements Filter {}
    public record NotInFilter(String name)      implements Filter {}
    /** Keep only objects in the dominator subtree of any object in the named bitset. */
    public record RetainedByFilter(String name)   implements Filter {}
    /** Keep only objects whose retained size satisfies the comparison. */
    public record RetainingFilter(String op, long size) implements Filter {}
    /**
     * Keep only objects whose runtime class is {@code className} or any subclass.
     * When {@code exactly=true}, only the exact class is matched (no subclasses).
     */
    public record OfTypeFilter(String className, boolean exactly) implements Filter {}
    /** Keep only objects whose shallow size satisfies the comparison. */
    public record SizedFilter(String op, long size) implements Filter {}
    /** Keep objects that have a direct reference to any object in the named set (reverse-refs). */
    public record ReferencingFilter(String name)   implements Filter {}
    /** Keep objects that are directly referenced by any object in the named set (forward-refs). */
    public record ReferencedByFilter(String name)  implements Filter {}
    /** Keep objects transitively reachable (by following forward refs) from any object in the named set. */
    public record ReachableFromFilter(String name) implements Filter {}
    /**
     * Keep objects whose primitive field satisfies the comparison.
     * {@code rawValue} is the literal token from the DSL (numeric or {@code true}/{@code false}).
     */
    public record WhereFilter(String fieldName, String op, String rawValue) implements Filter {}

    public sealed interface Terminal {}
    public record TopNTerminal(int n)         implements Terminal {}
    public record BottomNTerminal(int n)      implements Terminal {}
    public record AggregateCountTerminal()    implements Terminal {}
    /** {@code func} is {@code "MAX"} or {@code "SUM"}. */
    public record AggregateRetainedSizeTerminal(String func) implements Terminal {}

    // ── Top-level Query types ─────────────────────────────────────────────────

    public sealed interface Query {}

    /** {@code ALL <class>} alone — all instances as a bitset; implicit top-N display. */
    public record AllSource(String className) implements Query {}
    public record AllTopByRetainedSize(String className, int n) implements Query {}
    /** Smallest-retained-size N instances of className. */
    public record AllBottomByRetainedSize(String className, int n) implements Query {}
    /** All instances with retained size satisfying the comparison. */
    public record AllRetaining(String className, String op, long size) implements Query {}
    public record AggregateCount(String className) implements Query {}
    /** MAX or SUM of retainedSize across all instances of className. */
    public record AggregateRetainedSize(String className, String func) implements Query {}
    public record ClassesQuery(String glob) implements Query {} // null = all classes
    public record ExplainQuery(int denseId) implements Query {}
    /** All objects in the dominator subtree of denseId, optionally limited to top n. */
    public record DominatorSubtree(int denseId, int topN) implements Query {} // topN=-1 = all
    /**
     * A composable pipeline: source → filters → terminal.
     * {@code terminal == null} means implicit top-N display; result stored as BitSetAnswer.
     */
    public record Pipeline(Source source, List<Filter> filters, Terminal terminal) implements Query {}
    public record StatusQuery() implements Query {}

    private DslParser() {}

    public static Query parse(String input) {
        String[] tokens = input.strip().split("\\s+");
        if (tokens.length == 0) throw new IllegalArgumentException("Empty query");

        return switch (tokens[0].toUpperCase()) {
            case "CLASS"    -> parseClass(tokens, input);
            case "TOP"      -> parseClass(withClassAndStar(tokens), input);
            case "BOTTOM"   -> parseClass(withClassAndStar(tokens), input);
            case "FROM"     -> parseFrom(tokens, input);
            case "CLASSES"  -> parseClasses(tokens);
            case "EXPLAIN"  -> parseExplain(tokens);
            case "RETAINED" -> parseRetainedBy(tokens);
            case "STATUS"   -> new StatusQuery();
            default         -> throw new IllegalArgumentException("Unrecognised query: " + input);
        };
    }

    private static Query parseClass(String[] tokens, String input) {
        if (tokens.length < 2) throw bad(input);
        String className = tokens[1];

        // CLASS <class> alone — a bitset source with no output terminal
        if (tokens.length == 2) return new AllSource(className);

        // Detect pipeline filter keywords — route to Pipeline
        if (tokens[2].equalsIgnoreCase("IN")
                || (tokens[2].equalsIgnoreCase("NOT") && tokens.length > 3
                    && tokens[3].equalsIgnoreCase("IN"))
                || (tokens[2].equalsIgnoreCase("RETAINED") && tokens.length > 4
                    && tokens[3].equalsIgnoreCase("BY")
                    && !tokens[4].matches("i\\d+"))
                || (tokens[2].equalsIgnoreCase("OF") && tokens.length > 4
                    && tokens[3].equalsIgnoreCase("TYPE"))
                || (tokens[2].equalsIgnoreCase("SIZED") && tokens.length > 4
                    && Set.of(">", ">=", "<", "<=", "=").contains(tokens[3]))
                || (tokens[2].equalsIgnoreCase("REFERENCING") && tokens.length > 3)
                || (tokens[2].equalsIgnoreCase("REFERENCED") && tokens.length > 4
                    && tokens[3].equalsIgnoreCase("BY"))
                || (tokens[2].equalsIgnoreCase("REACHABLE") && tokens.length > 4
                    && tokens[3].equalsIgnoreCase("FROM"))
                || (tokens[2].equalsIgnoreCase("WHERE") && tokens.length > 5
                    && Set.of(">", ">=", "<", "<=", "=").contains(tokens[4]))) {
            return parsePipeline(new ClassSource(className), tokens, 2, input);
        }

        // CLASS <class> TOP n [BY retainedSize]
        if (tokens.length >= 4 && tokens[2].equalsIgnoreCase("TOP")) {
            boolean byOk = tokens.length == 4
                || (tokens.length >= 6 && tokens[4].equalsIgnoreCase("BY")
                    && tokens[5].equalsIgnoreCase("retainedSize"));
            if (byOk) return new AllTopByRetainedSize(className, parseInt(tokens[3], input));
        }

        // CLASS <class> BOTTOM n [BY retainedSize]
        if (tokens.length >= 4 && tokens[2].equalsIgnoreCase("BOTTOM")) {
            boolean byOk = tokens.length == 4
                || (tokens.length >= 6 && tokens[4].equalsIgnoreCase("BY")
                    && tokens[5].equalsIgnoreCase("retainedSize"));
            if (byOk) return new AllBottomByRetainedSize(className, parseInt(tokens[3], input));
        }

        // CLASS <class> RETAINING op n
        if (tokens.length >= 5
                && tokens[2].equalsIgnoreCase("RETAINING")) {
            String op  = tokens[3];
            if (!Set.of(">", ">=", "<", "<=", "=").contains(op))
                throw new IllegalArgumentException("Unknown operator: " + op);
            long size = parseLong(tokens[4], input);
            return new AllRetaining(className, op, size);
        }

        // CLASS <class> COUNT
        if (tokens.length == 3 && tokens[2].equalsIgnoreCase("COUNT")) {
            return new AggregateCount(className);
        }

        // CLASS <class> SUM retainedSize
        // CLASS <class> MAX retainedSize
        if (tokens.length == 4
                && (tokens[2].equalsIgnoreCase("SUM") || tokens[2].equalsIgnoreCase("MAX"))
                && tokens[3].equalsIgnoreCase("retainedSize")) {
            return new AggregateRetainedSize(className, tokens[2].toUpperCase());
        }

        throw bad(input);
    }

    private static Query parseFrom(String[] tokens, String input) {
        if (tokens.length < 2) throw bad(input);
        Source source = tokens[1].equalsIgnoreCase("THAT")
            ? new ThatSource()
            : new NameSource(tokens[1]);
        return parsePipeline(source, tokens, 2, input);
    }

    private static Query parsePipeline(Source source, String[] tokens, int idx, String input) {
        var filters = new ArrayList<Filter>();

        while (idx < tokens.length) {
            if (tokens[idx].equalsIgnoreCase("IN")) {
                if (idx + 1 >= tokens.length) throw bad(input);
                filters.add(new InFilter(tokens[idx + 1]));
                idx += 2;
            } else if (tokens[idx].equalsIgnoreCase("NOT")
                    && idx + 2 < tokens.length
                    && tokens[idx + 1].equalsIgnoreCase("IN")) {
                filters.add(new NotInFilter(tokens[idx + 2]));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("RETAINED")
                    && idx + 2 < tokens.length
                    && tokens[idx + 1].equalsIgnoreCase("BY")
                    && !tokens[idx + 2].startsWith("#")) {
                filters.add(new RetainedByFilter(tokens[idx + 2]));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("RETAINING")
                    && idx + 2 < tokens.length
                    && Set.of(">", ">=", "<", "<=", "=").contains(tokens[idx + 1])) {
                long size = parseLong(tokens[idx + 2], input);
                filters.add(new RetainingFilter(tokens[idx + 1], size));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("OF")
                    && idx + 2 < tokens.length
                    && tokens[idx + 1].equalsIgnoreCase("TYPE")) {
                if (tokens[idx + 2].equalsIgnoreCase("EXACTLY") && idx + 3 < tokens.length) {
                    filters.add(new OfTypeFilter(tokens[idx + 3], true));
                    idx += 4;
                } else {
                    filters.add(new OfTypeFilter(tokens[idx + 2], false));
                    idx += 3;
                }
            } else if (tokens[idx].equalsIgnoreCase("SIZED")
                    && idx + 2 < tokens.length
                    && Set.of(">", ">=", "<", "<=", "=").contains(tokens[idx + 1])) {
                long size = parseLong(tokens[idx + 2], input);
                filters.add(new SizedFilter(tokens[idx + 1], size));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("REFERENCING")
                    && idx + 1 < tokens.length) {
                filters.add(new ReferencingFilter(tokens[idx + 1]));
                idx += 2;
            } else if (tokens[idx].equalsIgnoreCase("REFERENCED")
                    && idx + 2 < tokens.length
                    && tokens[idx + 1].equalsIgnoreCase("BY")) {
                filters.add(new ReferencedByFilter(tokens[idx + 2]));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("REACHABLE")
                    && idx + 2 < tokens.length
                    && tokens[idx + 1].equalsIgnoreCase("FROM")) {
                filters.add(new ReachableFromFilter(tokens[idx + 2]));
                idx += 3;
            } else if (tokens[idx].equalsIgnoreCase("WHERE")
                    && idx + 3 < tokens.length
                    && Set.of(">", ">=", "<", "<=", "=").contains(tokens[idx + 2])) {
                filters.add(new WhereFilter(tokens[idx + 1], tokens[idx + 2], tokens[idx + 3]));
                idx += 4;
            } else {
                break;
            }
        }

        Terminal terminal = idx < tokens.length ? parseTerminal(tokens, idx, input) : null;
        return new Pipeline(source, List.copyOf(filters), terminal);
    }

    private static Terminal parseTerminal(String[] tokens, int i, String input) {
        return switch (tokens[i].toUpperCase()) {
            case "TOP" -> {
                if (i + 1 >= tokens.length) throw bad(input);
                boolean byOk = i + 2 >= tokens.length
                    || (i + 3 < tokens.length && tokens[i + 2].equalsIgnoreCase("BY")
                        && tokens[i + 3].equalsIgnoreCase("retainedSize"));
                if (byOk) yield new TopNTerminal(parseInt(tokens[i + 1], input));
                throw bad(input);
            }
            case "BOTTOM" -> {
                if (i + 1 >= tokens.length) throw bad(input);
                boolean byOk = i + 2 >= tokens.length
                    || (i + 3 < tokens.length && tokens[i + 2].equalsIgnoreCase("BY")
                        && tokens[i + 3].equalsIgnoreCase("retainedSize"));
                if (byOk) yield new BottomNTerminal(parseInt(tokens[i + 1], input));
                throw bad(input);
            }
            case "COUNT" -> new AggregateCountTerminal();
            case "SUM" -> {
                if (i + 1 >= tokens.length || !tokens[i + 1].equalsIgnoreCase("retainedSize"))
                    throw bad(input);
                yield new AggregateRetainedSizeTerminal("SUM");
            }
            case "MAX" -> {
                if (i + 1 >= tokens.length || !tokens[i + 1].equalsIgnoreCase("retainedSize"))
                    throw bad(input);
                yield new AggregateRetainedSizeTerminal("MAX");
            }
            default -> throw bad(input);
        };
    }

    private static Query parseRetainedBy(String[] tokens) {
        // RETAINED BY iN [TOP n [BY retainedSize]]
        if (tokens.length < 3
                || !tokens[1].equalsIgnoreCase("BY")
                || !tokens[2].matches("i\\d+")) {
            throw new IllegalArgumentException("Usage: RETAINED BY i<id> [TOP n [BY retainedSize]]");
        }
        int denseId = Integer.parseInt(tokens[2].substring(1));
        int topN = -1;
        if (tokens.length >= 5 && tokens[3].equalsIgnoreCase("TOP")) {
            boolean byOk = tokens.length == 5
                || (tokens.length >= 7 && tokens[5].equalsIgnoreCase("BY")
                    && tokens[6].equalsIgnoreCase("retainedSize"));
            if (byOk) topN = Integer.parseInt(tokens[4]);
        }
        return new DominatorSubtree(denseId, topN);
    }

    private static Query parseClasses(String[] tokens) {
        if (tokens.length == 1) return new ClassesQuery(null);
        if (tokens.length >= 3 && tokens[1].equalsIgnoreCase("MATCHING")) {
            return new ClassesQuery(tokens[2]);
        }
        throw new IllegalArgumentException("Usage: CLASSES [MATCHING <glob>]");
    }

    private static Query parseExplain(String[] tokens) {
        if (tokens.length < 2) throw new IllegalArgumentException("Usage: EXPLAIN i<id>");
        String id = tokens[1];
        if (!id.matches("i\\d+")) throw new IllegalArgumentException("Object ID must be i<number> (e.g. i12345)");
        try {
            return new ExplainQuery(Integer.parseInt(id.substring(1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid object ID: " + id);
        }
    }

    private static int parseInt(String s, String ctx) {
        try {
            int n = Integer.parseInt(s);
            if (n <= 0) throw new IllegalArgumentException("Must be positive: " + s);
            return n;
        } catch (NumberFormatException e) {
            throw bad(ctx);
        }
    }

    private static long parseLong(String s, String ctx) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw bad(ctx);
        }
    }

    // Turns ["TOP", ...] into ["CLASS", "*", "TOP", ...] for standalone TOP/BOTTOM
    private static String[] withClassAndStar(String[] tokens) {
        String[] result = new String[tokens.length + 2];
        result[0] = "CLASS";
        result[1] = "*";
        System.arraycopy(tokens, 0, result, 2, tokens.length);
        return result;
    }

    private static IllegalArgumentException bad(String input) {
        return new IllegalArgumentException("Unrecognised query: " + input);
    }
}
