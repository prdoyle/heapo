package heapo.query_engine;

import java.util.Set;

/**
 * DSL parser. Supported queries:
 * <ul>
 *   <li>{@code ALL <class> TOP <n> BY retainedSize}
 *   <li>{@code ALL <class> BOTTOM <n> BY retainedSize}
 *   <li>{@code ALL <class> RETAINING > <bytes>}
 *   <li>{@code ALL <class> AGGREGATE COUNT}
 *   <li>{@code ALL <class> AGGREGATE MAX retainedSize}
 *   <li>{@code ALL <class> AGGREGATE SUM retainedSize}
 *   <li>{@code CLASSES [MATCHING <glob>]}
 *   <li>{@code EXPLAIN #<denseId>}
 *   <li>{@code DOMINATOR SUBTREE OF #<id> [TOP n BY retainedSize]}
 *   <li>{@code STATUS}
 * </ul>
 * {@code <class>} is a fully-qualified dotted class name or {@code *} for all objects.
 */
public final class DslParser {

    public sealed interface Query permits
            AllTopByRetainedSize,
            AllBottomByRetainedSize,
            AllRetaining,
            AggregateCount,
            AggregateRetainedSize,
            ClassesQuery,
            ExplainQuery,
            DominatorSubtree,
            StatusQuery {}

    public record AllTopByRetainedSize(String className, int n) implements Query {}
    /** Smallest-retained-size N instances of className. */
    public record AllBottomByRetainedSize(String className, int n) implements Query {}
    /** All instances with retained size satisfying the comparison (op = ">", ">=", "<", "<="). */
    public record AllRetaining(String className, String op, long size) implements Query {}
    public record AggregateCount(String className) implements Query {}
    /** MAX or SUM of retainedSize across all instances of className. */
    public record AggregateRetainedSize(String className, String func) implements Query {}
    public record ClassesQuery(String glob) implements Query {} // null = all classes
    public record ExplainQuery(int denseId) implements Query {}
    /** All objects in the dominator subtree of denseId, optionally limited to top n. */
    public record DominatorSubtree(int denseId, int topN) implements Query {} // topN=-1 = all
    public record StatusQuery() implements Query {}

    private DslParser() {}

    public static Query parse(String input) {
        String[] tokens = input.strip().split("\\s+");
        if (tokens.length == 0) throw new IllegalArgumentException("Empty query");

        return switch (tokens[0].toUpperCase()) {
            case "ALL"       -> parseAll(tokens, input);
            case "TOP"       -> parseAll(withAllAndClass("*", tokens), input);
            case "BOTTOM"    -> parseAll(withAllAndClass("*", tokens), input);
            case "CLASSES"   -> parseClasses(tokens);
            case "EXPLAIN"   -> parseExplain(tokens);
            case "DOMINATOR" -> parseDominatorSubtree(tokens);
            case "STATUS"    -> new StatusQuery();
            default          -> throw new IllegalArgumentException("Unrecognised query: " + input);
        };
    }

    private static Query parseAll(String[] tokens, String input) {
        if (tokens.length < 3) throw bad(input);
        String className = tokens[1];

        // ALL <class> TOP n BY retainedSize
        if (tokens.length >= 6
                && tokens[2].equalsIgnoreCase("TOP")
                && tokens[4].equalsIgnoreCase("BY")
                && tokens[5].equalsIgnoreCase("retainedSize")) {
            return new AllTopByRetainedSize(className, parseInt(tokens[3], input));
        }

        // ALL <class> BOTTOM n BY retainedSize
        if (tokens.length >= 6
                && tokens[2].equalsIgnoreCase("BOTTOM")
                && tokens[4].equalsIgnoreCase("BY")
                && tokens[5].equalsIgnoreCase("retainedSize")) {
            return new AllBottomByRetainedSize(className, parseInt(tokens[3], input));
        }

        // ALL <class> RETAINING op n
        if (tokens.length >= 5
                && tokens[2].equalsIgnoreCase("RETAINING")) {
            String op  = tokens[3];
            if (!Set.of(">", ">=", "<", "<=", "=").contains(op))
                throw new IllegalArgumentException("Unknown operator: " + op);
            long size = parseLong(tokens[4], input);
            return new AllRetaining(className, op, size);
        }

        // ALL <class> AGGREGATE COUNT
        if (tokens.length >= 4
                && tokens[2].equalsIgnoreCase("AGGREGATE")
                && tokens[3].equalsIgnoreCase("COUNT")) {
            return new AggregateCount(className);
        }

        // ALL <class> AGGREGATE MAX retainedSize
        // ALL <class> AGGREGATE SUM retainedSize
        if (tokens.length >= 5
                && tokens[2].equalsIgnoreCase("AGGREGATE")
                && (tokens[3].equalsIgnoreCase("MAX") || tokens[3].equalsIgnoreCase("SUM"))
                && tokens[4].equalsIgnoreCase("retainedSize")) {
            return new AggregateRetainedSize(className, tokens[3].toUpperCase());
        }

        throw bad(input);
    }

    private static Query parseDominatorSubtree(String[] tokens) {
        // DOMINATOR SUBTREE OF #id [TOP n BY retainedSize]
        if (tokens.length < 4
                || !tokens[1].equalsIgnoreCase("SUBTREE")
                || !tokens[2].equalsIgnoreCase("OF")
                || !tokens[3].startsWith("#")) {
            throw new IllegalArgumentException("Usage: DOMINATOR SUBTREE OF #<id> [TOP n BY retainedSize]");
        }
        int denseId = Integer.parseInt(tokens[3].substring(1));
        int topN = -1;
        if (tokens.length >= 8
                && tokens[4].equalsIgnoreCase("TOP")
                && tokens[6].equalsIgnoreCase("BY")
                && tokens[7].equalsIgnoreCase("retainedSize")) {
            topN = Integer.parseInt(tokens[5]);
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
        if (tokens.length < 2) throw new IllegalArgumentException("Usage: EXPLAIN #<id>");
        String id = tokens[1];
        if (!id.startsWith("#")) throw new IllegalArgumentException("Dense ID must start with #");
        try {
            return new ExplainQuery(Integer.parseInt(id.substring(1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid dense ID: " + id);
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

    // Turns ["TOP", ...] into ["ALL", className, "TOP", ...] for reuse by parseAll
    private static String[] withAllAndClass(String className, String[] tokens) {
        String[] result = new String[tokens.length + 2];
        result[0] = "ALL";
        result[1] = className;
        System.arraycopy(tokens, 0, result, 2, tokens.length);
        return result;
    }

    private static IllegalArgumentException bad(String input) {
        return new IllegalArgumentException("Unrecognised query: " + input);
    }
}
