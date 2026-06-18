package heapo.query_engine;

/**
 * Minimal DSL parser for Phase 4.
 * Supports: {@code ALL <className> TOP <n> BY retainedSize}
 * where className is a fully-qualified dotted class name or {@code *}.
 */
public final class DslParser {

    public sealed interface Query permits AllTopByRetainedSize {}

    public record AllTopByRetainedSize(String className, int n) implements Query {}

    private DslParser() {}

    /**
     * Parse a query string. Throws {@link IllegalArgumentException} if the syntax is
     * unrecognised.
     */
    public static Query parse(String input) {
        String[] tokens = input.strip().split("\\s+");
        if (tokens.length >= 5
                && tokens[0].equalsIgnoreCase("ALL")
                && tokens[2].equalsIgnoreCase("TOP")
                && tokens[4].equalsIgnoreCase("BY")
                && tokens.length >= 6
                && tokens[5].equalsIgnoreCase("retainedSize")) {
            String className = tokens[1];
            int    n;
            try {
                n = Integer.parseInt(tokens[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Expected integer after TOP, got: " + tokens[3]);
            }
            if (n <= 0) throw new IllegalArgumentException("TOP count must be positive");
            return new AllTopByRetainedSize(className, n);
        }
        throw new IllegalArgumentException("Unrecognised query: " + input);
    }
}
