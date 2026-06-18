package heapo.query_engine;

/**
 * Minimal DSL parser (Phase 6). Supported queries:
 * <ul>
 *   <li>{@code ALL <class> TOP <n> BY retainedSize}
 *   <li>{@code ALL <class> AGGREGATE COUNT}
 *   <li>{@code CLASSES [MATCHING <glob>]}
 *   <li>{@code EXPLAIN #<denseId>}
 *   <li>{@code STATUS}
 * </ul>
 * {@code <class>} is a fully-qualified dotted class name or {@code *} for all objects.
 */
public final class DslParser {

    public sealed interface Query permits
            AllTopByRetainedSize,
            AggregateCount,
            ClassesQuery,
            ExplainQuery,
            StatusQuery {}

    public record AllTopByRetainedSize(String className, int n) implements Query {}
    public record AggregateCount(String className) implements Query {}
    public record ClassesQuery(String glob) implements Query {} // null = all classes
    public record ExplainQuery(int denseId) implements Query {}
    public record StatusQuery() implements Query {}

    private DslParser() {}

    public static Query parse(String input) {
        String[] tokens = input.strip().split("\\s+");
        if (tokens.length == 0) throw new IllegalArgumentException("Empty query");

        return switch (tokens[0].toUpperCase()) {
            case "ALL"     -> parseAll(tokens, input);
            case "CLASSES" -> parseClasses(tokens);
            case "EXPLAIN" -> parseExplain(tokens);
            case "STATUS"  -> new StatusQuery();
            default        -> throw new IllegalArgumentException("Unrecognised query: " + input);
        };
    }

    private static Query parseAll(String[] tokens, String input) {
        if (tokens.length < 3) throw bad(input);

        String className = tokens[1];

        if (tokens.length >= 5
                && tokens[2].equalsIgnoreCase("TOP")
                && tokens[4].equalsIgnoreCase("BY")
                && tokens.length >= 6
                && tokens[5].equalsIgnoreCase("retainedSize")) {
            int n = parseInt(tokens[3], input);
            return new AllTopByRetainedSize(className, n);
        }

        if (tokens.length >= 4
                && tokens[2].equalsIgnoreCase("AGGREGATE")
                && tokens[3].equalsIgnoreCase("COUNT")) {
            return new AggregateCount(className);
        }

        throw bad(input);
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

    private static IllegalArgumentException bad(String input) {
        return new IllegalArgumentException("Unrecognised query: " + input);
    }
}
