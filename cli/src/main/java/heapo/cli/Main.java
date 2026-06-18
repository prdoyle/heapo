package heapo.cli;

import heapo.indexes.IndexRegistry;
import heapo.query_engine.*;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name        = "heapo",
    description = "Heap dump analysis via dominator tree",
    mixinStandardHelpOptions = true,
    version     = "0.1.0",
    subcommands = {
        Main.OpenCommand.class,
        Main.QueryCommand.class,
        Main.UnpackCommand.class,
        Main.SkillCommand.class,
        HelpCommand.class
    }
)
public final class Main implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
        System.exit(new CommandLine(new Main()).execute(args));
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    static final String REPL_HELP = """
        DSL sources (produce a bitset):
          ALL <class>                             all instances of a class (implicit top 10 display)
          FROM <name>                             named bitset result
          FROM THAT                               current result
          Use * as class name for all objects.

        Built-in names (usable in FROM / IN / RETAINED BY without CALL THAT):
          GcRoots         all GC root objects
          Threads         all java.lang.Thread instances
          ClassLoaders    all java.lang.ClassLoader instances
          SoftReferences / WeakReferences / PhantomReferences

        Bitset filters (chain after a source):
          IN <name>                               bitset AND — keep objects in both sets
          NOT IN <name>                           bitset AND-NOT — exclude objects in set
          RETAINED BY <name>                      keep objects dominated by any object in set
          RETAINING > <bytes>                     keep objects whose retained size satisfies comparison (>, >=, <, <=, =)
          OF TYPE <class>                         keep objects of class or any subclass
          OF TYPE EXACTLY <class>                 keep objects of exactly that class
          SIZED > <bytes>                         keep objects whose shallow size satisfies comparison (>, >=, <, <=, =)
          REFERENCING <name>                      keep objects that directly reference any object in set
          REFERENCED BY <name>                    keep objects directly referenced by any object in set

        Output terminals (materialise the bitset):
          TOP <n> BY retainedSize                 largest-N objects
          BOTTOM <n> BY retainedSize              smallest-N objects
          AGGREGATE COUNT                         total count
          AGGREGATE MAX retainedSize              max retained size
          AGGREGATE SUM retainedSize              total retained size

        Combined shorthand (source + terminal in one line):
          ALL <class> TOP <n> BY retainedSize
          ALL <class> BOTTOM <n> BY retainedSize
          ALL <class> RETAINING > <bytes>         filter by retained size (>, >=, <, <=, =)
          ALL <class> AGGREGATE COUNT|MAX|SUM retainedSize
          TOP <n> BY retainedSize                 across all classes
          BOTTOM <n> BY retainedSize              across all classes

        Other queries:
          STATUS                                  object and class counts
          CLASSES [MATCHING <glob>]               all classes sorted by instance count
          EXPLAIN #<id>                           dominator chain to GC root
          RETAINED BY #<id> [TOP <n> BY retainedSize]   objects retained by #<id>

        Session commands:
          NAMES [MATCHING <glob>]  show named results (optionally filtered)
          EXPLAIN <name>           show what command produced the named result
          CALL THAT <name>         name the last result (persists a bitset to disk)
          CALL @<id> <name>      name a specific history entry
          FORGET <name>          remove a name
          UNDO                   reverse the last CALL or FORGET
          HISTORY [<n>]          show recent commands (default 10)

        SQL:  any SELECT … FROM <result_table> … is passed to SQLite
        exit / quit / Ctrl-D    leave the REPL
        """;

    static Path resolveOutDir(Path hprofFile, Path heapDir) {
        return heapDir != null ? heapDir
             : hprofFile.resolveSibling(hprofFile.getFileName() + ".d");
    }

    // ── open ──────────────────────────────────────────────────────────────────

    @Command(
        name        = "open",
        description = "Build all indexes and start an interactive REPL for exploring the heap"
    )
    static final class OpenCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the HPROF file")
        Path hprofFile;

        @Option(names = {"-d", "--heap-dir"},
                description = "Directory for unpacked indexes (default: <hprof>.d/)")
        Path heapDir;

        @Option(names = {"--clean"},
                description = "Clear history and name bindings at startup (result data is kept)")
        boolean clean;

        @Override
        public Integer call() throws IOException, SQLException {
            Path outDir = resolveOutDir(hprofFile, heapDir);
            Files.createDirectories(outDir);

            UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir);
            var          reg  = new IndexRegistry(heap);
            reg.buildAll();

            Path dbPath = outDir.resolve("sql.db");
            try (var terminal = TerminalBuilder.builder().build();
                 var session  = new HeapSession(heap, reg, dbPath)) {
                if (clean) session.clearSession();

                var reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new DefaultHistory())
                    .completer(new DslCompleter(heap, session.names()))
                    .build();

                while (true) {
                    String line;
                    try {
                        line = reader.readLine("heapo> ");
                    } catch (EndOfFileException | UserInterruptException e) {
                        break;
                    }
                    if (line == null) break;
                    String trimmed = line.strip();
                    if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) break;
                    if (trimmed.equalsIgnoreCase("help") || trimmed.equalsIgnoreCase("?")) {
                        System.out.print(REPL_HELP);
                        continue;
                    }
                    if (trimmed.isEmpty()) {
                        // Blank input: re-display THAT (handy for reviewing last result)
                        try {
                            String output = session.execute("THAT");
                            if (!output.contains("\"error\"") && !output.isEmpty()) {
                                System.out.print(OutputFormatter.convert(output, OutputFormatter.Format.HUMAN));
                                System.out.println();
                            }
                        } catch (Exception ignored) {}
                        continue;
                    }

                    try {
                        String output = session.execute(trimmed);
                        if (!output.isEmpty()) {
                            System.out.print(OutputFormatter.convert(output, OutputFormatter.Format.HUMAN));
                            System.out.println(); // blank line separator after each result
                        }
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
            return 0;
        }
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Command(
        name        = "query",
        description = "Run a single DSL query against a heap dump and exit"
    )
    static final class QueryCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the HPROF file")
        Path hprofFile;

        @Parameters(index = "1..*", arity = "1..*",
                    description = "Query tokens (e.g. ALL * TOP 20 BY retainedSize)")
        List<String> queryTokens;

        @Option(names = {"-d", "--heap-dir"},
                description = "Directory for unpacked indexes (default: <hprof>.d/)")
        Path heapDir;

        @Option(names = {"--output"},
                description = "Output format: human (default), jsonl, json",
                defaultValue = "human")
        String outputFormat;

        @Override
        public Integer call() throws IOException, SQLException {
            Path outDir = resolveOutDir(hprofFile, heapDir);
            Files.createDirectories(outDir);

            UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir);
            var          reg  = new IndexRegistry(heap);

            String queryStr = String.join(" ", queryTokens);

            DslParser.Query parsed;
            try {
                parsed = DslParser.parse(queryStr);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }

            String jsonl = switch (parsed) {
                case DslParser.Pipeline p -> {
                    boolean hasSessionFilter = p.filters().stream().anyMatch(
                        f -> f instanceof DslParser.InFilter
                          || f instanceof DslParser.NotInFilter
                          || f instanceof DslParser.RetainedByFilter);
                    if (p.source() instanceof DslParser.NameSource
                            || p.source() instanceof DslParser.ThatSource
                            || hasSessionFilter) {
                        System.err.println(
                            "Error: name/THAT resolution requires a session — use 'heapo open'");
                        yield null;
                    }
                    var cs   = (DslParser.ClassSource) p.source();
                    long[] b = QueryEngine.buildBitSet(heap, reg, cs.className());
                    // Apply session-independent filters
                    for (var filter : p.filters()) {
                        if (filter instanceof DslParser.RetainingFilter f) {
                            int objectCount = heap.objectCount();
                            long[] result = new long[b.length];
                            try (var retainedSize = reg.openRetainedSize()) {
                                for (int v = 0; v < objectCount; v++) {
                                    if ((b[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                                        long rs = retainedSize.readLong(v);
                                        boolean matches = switch (f.op()) {
                                            case ">"  -> rs >  f.size();
                                            case ">=" -> rs >= f.size();
                                            case "<"  -> rs <  f.size();
                                            case "<=" -> rs <= f.size();
                                            case "="  -> rs == f.size();
                                            default   -> false;
                                        };
                                        if (matches) result[v >>> 6] |= 1L << (v & 63);
                                    }
                                }
                            }
                            b = result;
                        } else if (filter instanceof DslParser.OfTypeFilter f) {
                            long[] typeBits = QueryEngine.buildOfTypeBitSet(heap, reg, f.className(), f.exactly());
                            long[] result = new long[b.length];
                            int len = Math.min(b.length, typeBits.length);
                            for (int i = 0; i < len; i++) result[i] = b[i] & typeBits[i];
                            b = result;
                        }
                    }
                    yield switch (p.terminal()) {
                        case null                                       ->
                            JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, 10));
                        case DslParser.TopNTerminal t                   ->
                            JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, t.n()));
                        case DslParser.BottomNTerminal t                ->
                            JsonlFormatter.formatTopN(QueryEngine.bottomNFromBitSet(heap, reg, b, t.n()));
                        case DslParser.AggregateCountTerminal ignored   ->
                            "{\"count\":" + QueryEngine.bitSetCardinality(b) + "}\n";
                        case DslParser.AggregateRetainedSizeTerminal t  ->
                            JsonlFormatter.formatAggregateRetainedSize(
                                "(pipeline)", t.func(),
                                QueryEngine.aggregateFromBitSet(heap, reg, b, t.func()));
                    };
                }
                case DslParser.AllSource q -> {
                    long[] bits = QueryEngine.buildBitSet(heap, reg, q.className());
                    yield JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, bits, 10));
                }
                case DslParser.AllTopByRetainedSize q ->
                    JsonlFormatter.formatTopN(QueryEngine.allTopByRetainedSize(heap, reg, q.className(), q.n()));
                case DslParser.AllBottomByRetainedSize q ->
                    JsonlFormatter.formatTopN(QueryEngine.allBottomByRetainedSize(heap, reg, q.className(), q.n()));
                case DslParser.AllRetaining q ->
                    JsonlFormatter.formatTopN(QueryEngine.allRetaining(heap, reg, q.className(), q.op(), q.size()));
                case DslParser.AggregateCount q ->
                    JsonlFormatter.formatCount(q.className(), QueryEngine.aggregateCount(heap, reg, q.className()));
                case DslParser.AggregateRetainedSize q ->
                    JsonlFormatter.formatAggregateRetainedSize(q.className(), q.func(),
                        QueryEngine.aggregateRetainedSize(heap, reg, q.className(), q.func()));
                case DslParser.ClassesQuery q ->
                    JsonlFormatter.formatClasses(QueryEngine.classes(heap, reg, q.glob()));
                case DslParser.ExplainQuery q ->
                    JsonlFormatter.formatExplain(QueryEngine.explain(heap, reg, q.denseId()));
                case DslParser.DominatorSubtree q ->
                    JsonlFormatter.formatTopN(QueryEngine.dominatorSubtree(heap, reg, q.denseId(), q.topN()));
                case DslParser.StatusQuery ignored ->
                    "{\"objectCount\":" + heap.objectCount() + ",\"classCount\":" + heap.classCount() + "}\n";
            };

            if (jsonl == null) return 1; // error already printed

            OutputFormatter.Format fmt;
            try {
                fmt = OutputFormatter.Format.valueOf(outputFormat.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Error: unknown output format '" + outputFormat + "'. Use jsonl, json, or human.");
                return 1;
            }
            System.out.print(OutputFormatter.convert(jsonl, fmt));
            return 0;
        }
    }

    // ── unpack ────────────────────────────────────────────────────────────────

    @Command(
        name        = "unpack",
        description = "Unpack a heap dump and build all indexes without entering the REPL"
    )
    static final class UnpackCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the HPROF file")
        Path hprofFile;

        @Option(names = {"-d", "--heap-dir"},
                description = "Directory for unpacked indexes (default: <hprof>.d/)")
        Path heapDir;

        @Override
        public Integer call() throws IOException {
            Path outDir = resolveOutDir(hprofFile, heapDir);
            Files.createDirectories(outDir);

            UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir);
            var          reg  = new IndexRegistry(heap);
            reg.buildAll();

            System.out.println("Indexes built: " + outDir.toAbsolutePath());
            return 0;
        }
    }

    // ── skill ─────────────────────────────────────────────────────────────────

    @Command(
        name        = "skill",
        description = "Print the Claude Code SKILL.md for this tool"
    )
    static final class SkillCommand implements Callable<Integer> {

        @Override
        public Integer call() throws IOException {
            try (var stream = SkillCommand.class.getResourceAsStream("/heapo/SKILL.md")) {
                if (stream == null) throw new IllegalStateException("SKILL.md resource not bundled in jar");
                System.out.print(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            return 0;
        }
    }
}
