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
import java.util.BitSet;
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
        Sigils: i<n> = heap object instance, t<n> = table result, s<n> = set (bitset), h<n> = history entry.
        The prompt shows the current result type and ID, e.g. heapo s42> or heapo t17>.

        DSL sources (produce a bitset):
          CLASS <class>                           all instances of a class (implicit top 10 display)
          FROM <bitset>                           named bitset result
          FROM THAT                               current result
          Use * as class name for all objects; * and ? wildcards are supported in class names.

        Built-in bitset names (usable anywhere a <bitset> is accepted):
          GcRoots         all GC root objects
          Threads         all java.lang.Thread instances
          ClassLoaders    all java.lang.ClassLoader instances
          SoftReferences / WeakReferences / PhantomReferences

        Bitset filters (chain after a source):
          IN <bitset>                             bitset AND — keep objects in both sets
          NOT IN <bitset>                         bitset AND-NOT — exclude objects in set
          RETAINED BY <bitset>                    keep objects dominated by any object in set
          RETAINING > <bytes>                     keep objects whose retained size satisfies comparison (>, >=, <, <=, =)
          OF TYPE <class>                         keep objects of class or any subclass
          OF TYPE EXACTLY <class>                 keep objects of exactly that class
          SIZED > <bytes>                         keep objects whose shallow size satisfies comparison (>, >=, <, <=, =)
          REFERENCING <bitset>                    keep objects that directly reference any object in set
          REFERENCED BY <bitset>                  keep objects directly referenced by any object in set
          REACHABLE FROM <bitset>                 keep objects transitively reachable from any object in set
          WHERE <field> <op> <value>              keep objects whose primitive field satisfies comparison (>, >=, <, <=, =)

        Output terminals (materialise the bitset into a table):
          TOP <n> [BY retainedSize]               largest-N objects
          BOTTOM <n> [BY retainedSize]            smallest-N objects
          COUNT                                   total count
          MAX retainedSize                        max retained size
          SUM retainedSize                        total retained size

        Combined shorthand (source + terminal in one line):
          CLASS <class> TOP <n> [BY retainedSize]
          CLASS <class> BOTTOM <n> [BY retainedSize]
          CLASS <class> RETAINING > <bytes>       filter by retained size (>, >=, <, <=, =)
          CLASS <class> COUNT|SUM retainedSize|MAX retainedSize
          TOP <n> [BY retainedSize]               across all classes
          BOTTOM <n> [BY retainedSize]            across all classes

        Other queries:
          STATUS                                  object and class counts
          CLASSES [MATCHING <glob>]               all classes sorted by instance count
          EXPLAIN i<id>                           dominator chain to GC root
          RETAINED BY i<id> [TOP <n>]             objects retained by i<id>

        Session commands:
          NAMES [MATCHING <glob>]  show named bitsets (optionally filtered)
          EXPLAIN <bitset>         show what command produced the named result
          CALL THAT <name>         name the last result (persists a bitset to disk)
          CALL h<id> <name>        name a specific history entry
          FORGET <bitset>          remove a name
          UNDO                     reverse the last CALL or FORGET
          HISTORY [<n>]            show recent commands (default 10)

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
                        String sigil = session.thatSigil();
                        String prompt = sigil.isEmpty() ? "heapo> " : "heapo " + sigil + "> ";
                        line = reader.readLine(prompt);
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
                    description = "Query tokens (e.g. CLASS * TOP 20 BY retainedSize)")
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

            String jsonl = switch (DslParser.parse(queryStr)) {
                case DslParser.Invalid e -> {
                    System.err.println("Error: " + e.message());
                    yield null;
                }
                case DslParser.Incomplete i -> {
                    System.err.println("Error: incomplete query; expected: "
                        + String.join(", ", i.completions()));
                    yield null;
                }
                case DslParser.Complete c -> executeQueryCommand(c.action(), heap, reg);
            };

            if (jsonl == null) return 1;

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

        private static String executeQueryCommand(DslParser.Query q,
                                                   UnpackedHeap heap,
                                                   IndexRegistry reg) throws IOException {
            return switch (q) {
                case DslParser.StatusQuery ignored ->
                    "{\"objectCount\":" + heap.objectCount()
                        + ",\"classCount\":" + heap.classCount() + "}\n";

                case DslParser.ClassesQuery cq ->
                    JsonlFormatter.formatClasses(QueryEngine.classes(heap, reg, cq.glob()));

                case DslParser.ExplainQuery eq ->
                    JsonlFormatter.formatExplain(QueryEngine.explain(heap, reg, eq.denseId()));

                case DslParser.DominatorSubtree ds ->
                    JsonlFormatter.formatTopN(
                        QueryEngine.dominatorSubtree(heap, reg, ds.denseId(), ds.topN()));

                case DslParser.Pipeline p -> executePipelineQuery(p, heap, reg);

                default -> {
                    System.err.println(
                        "Error: '" + q.getClass().getSimpleName()
                            + "' requires a session — use 'heapo open'");
                    yield null;
                }
            };
        }

        private static String executePipelineQuery(DslParser.Pipeline p,
                                                    UnpackedHeap heap,
                                                    IndexRegistry reg) throws IOException {
            boolean hasSessionDep = p.source() instanceof DslParser.NameSource
                || p.source() instanceof DslParser.ThatSource
                || p.filters().stream().anyMatch(
                    f -> f instanceof DslParser.InFilter
                      || f instanceof DslParser.NotInFilter
                      || f instanceof DslParser.RetainedByFilter);
            if (hasSessionDep) {
                System.err.println(
                    "Error: name/THAT resolution requires a session — use 'heapo open'");
                return null;
            }

            var cs = (DslParser.ClassSource) p.source();
            BitSet b = QueryEngine.buildBitSet(heap, reg, cs.className());
            String contextClass = cs.className().equals("*") ? null : cs.className();

            for (var filter : p.filters()) {
                if (filter instanceof DslParser.OfTypeFilter f) contextClass = f.className();
                if (filter instanceof DslParser.RetainingFilter f) {
                    BitSet result = new BitSet(heap.objectCount());
                    try (var rs = reg.openRetainedSize()) {
                        for (int v = b.nextSetBit(0); v >= 0; v = b.nextSetBit(v + 1)) {
                            long size = rs.readLong(v);
                            boolean ok = switch (f.op()) {
                                case ">"  -> size >  f.size();
                                case ">=" -> size >= f.size();
                                case "<"  -> size <  f.size();
                                case "<=" -> size <= f.size();
                                case "="  -> size == f.size();
                                default   -> false;
                            };
                            if (ok) result.set(v);
                        }
                    }
                    b = result;
                } else if (filter instanceof DslParser.OfTypeFilter f) {
                    BitSet typeBits = QueryEngine.buildOfTypeBitSet(heap, reg, f.className(), f.exactly());
                    b.and(typeBits);
                } else if (filter instanceof DslParser.WhereFilter f && contextClass != null) {
                    var nameIdx = ClassNameIndex.load(heap);
                    int cid = nameIdx.resolve(contextClass);
                    if (cid >= 0) {
                        long val = f.rawValue().equalsIgnoreCase("true")  ? 1L
                                 : f.rawValue().equalsIgnoreCase("false") ? 0L
                                 : Long.parseLong(f.rawValue());
                        b = QueryEngine.buildWhereFilterBitSet(heap, reg, b, cid,
                                f.field(), f.op(), val);
                    }
                }
            }

            return switch (p.terminal()) {
                case null ->
                    JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, 10));
                case DslParser.TopNTerminal t ->
                    JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, t.n()));
                case DslParser.BottomNTerminal t ->
                    JsonlFormatter.formatTopN(QueryEngine.bottomNFromBitSet(heap, reg, b, t.n()));
                case DslParser.AggregateCountTerminal ignored ->
                    "{\"count\":" + b.cardinality() + "}\n";
                case DslParser.AggregateRetainedSizeTerminal t ->
                    JsonlFormatter.formatAggregateRetainedSize("(pipeline)", t.func(),
                        QueryEngine.aggregateFromBitSet(heap, reg, b, t.func()));
            };
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
