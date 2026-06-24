package heapo.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import heapo.indexes.IndexRegistry;
import heapo.query_engine.*;
import heapo.session.SessionDb;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        Sigils: i<n> = heap object instance, s<n> = set (bitset) result, h<n> = history entry.
        The prompt shows the current result type and ID, e.g. heapo s42>.

        Grammar:  <source> [<filter>...] [<terminal>]
        A source is itself filterable: ALL RETAINED BY i123 REFERENCING i456 SHOW 10

        Sources (produce a bitset):
          ALL                                     all objects
          CLASS <class>                           instances of a class (* wildcard supported)
          THAT                                    current result (as a source)
          i<n>                                    singleton: just object i<n>
          <name>                                  named result or built-in name
          GcRoots / Threads / ClassLoaders / SoftReferences / WeakReferences / PhantomReferences

        Filters (chain after any source):
          IN <source>                             bitset AND — keep objects in both sets
          NOT IN <source>                         bitset AND-NOT — exclude objects in source
          RETAINED BY <source>                    keep objects dominated by any object in source
          RETAINING > <bytes>                     keep objects whose retained size satisfies comparison (>, >=, <, <=, =)
          OF TYPE <class>                         keep objects of class or any subclass
          OF TYPE EXACTLY <class>                 keep objects of exactly that class
          SIZED > <bytes>                         keep objects whose shallow size satisfies comparison (>, >=, <, <=, =)
          REFERENCING <source>                    keep objects that directly reference any object in source
          REFERENCED BY <source>                  keep objects directly referenced by any object in source
          REACHABLE FROM <source>                 keep objects transitively reachable from any object in source
          WHERE <field> <op> <value>              keep objects whose primitive field satisfies comparison (>, >=, <, <=, =)

        Terminals:
          TOP <n> [BY retainedSize]               narrow THAT to the N largest objects and display them
          SHOW <n> [BY retainedSize]              display N largest without changing THAT
          COUNT                                   total count
          MAX retainedSize                        max retained size
          SUM retainedSize                        total retained size
          (none)                                  display top 10 by retained size

        Other queries:
          TOP <n> [BY retainedSize]               largest-N across all objects (shorthand for ALL TOP <n>)
          SHOW <n> [BY retainedSize]              display-only across all objects
          STATUS                                  object and class counts
          CLASSES <glob>                          classes matching glob, sorted by instance count
          EXPLAIN i<id>                           dominator chain to GC root

        Session commands:
          NAMES [<glob>]           show named results (optionally filtered by glob)
          EXPLAIN <name>           show what command produced the named result
          CALL THAT <name>         name the last result (persists a bitset to disk)
          CALL h<id> <name>        name a specific history entry
          FORGET <name>            remove a name
          UNDO                     reverse the last CALL or FORGET
          HISTORY [<n>]            show recent commands (default 10)

        SQL:  any SELECT … FROM <result_table> … is passed to SQLite
        exit / quit / Ctrl-D    leave the REPL
        """;

    static java.util.function.Consumer<String> timedProgress() {
        long start = System.currentTimeMillis();
        return msg -> System.err.printf("[%5.1fs] %s%n", (System.currentTimeMillis() - start) / 1e3, msg);
    }

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

        @Option(names = {"--output"},
                description = "Output format: human (default), jsonl, json",
                defaultValue = "human")
        String outputFormat;

        @Override
        public Integer call() throws IOException, SQLException {
            OutputFormatter.Format fmt;
            try {
                fmt = OutputFormatter.Format.valueOf(outputFormat.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Error: unknown output format '" + outputFormat + "'. Use jsonl, json, or human.");
                return 1;
            }

            Path outDir = resolveOutDir(hprofFile, heapDir);
            Files.createDirectories(outDir);

            var progress = timedProgress();
            UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir, progress);
            var reg = new IndexRegistry(heap);
            reg.buildAll(progress);
            progress.accept("Done.");

            Path dbPath = outDir.resolve("sql.db");
            try (var terminal = TerminalBuilder.builder().build();
                 var session  = new HeapSession(heap, reg, dbPath)) {
                if (clean) session.clearSession();

                var reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new DefaultHistory())
                    .completer(new DslCompleter(heap, session.names()))
                    .variable(LineReader.HISTORY_FILE, outDir.resolve("repl-history"))
                    .build();

                boolean interactive = fmt == OutputFormatter.Format.HUMAN;

                while (true) {
                    String line;
                    try {
                        String sigil = session.thatSigil();
                        String prompt = sigil.isEmpty() ? "heapo> " : "heapo " + sigil + "> ";
                        line = reader.readLine(interactive ? prompt : "");
                    } catch (EndOfFileException | UserInterruptException e) {
                        break;
                    }
                    if (line == null) break;
                    String trimmed = line.strip();
                    if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) break;
                    if (trimmed.equalsIgnoreCase("help") || trimmed.equalsIgnoreCase("?")) {
                        if (interactive) System.out.print(REPL_HELP);
                        continue;
                    }
                    if (trimmed.isEmpty()) {
                        if (interactive) {
                            // Blank input: re-display THAT (handy for reviewing last result)
                            try {
                                String output = session.execute("THAT");
                                if (!output.contains("\"error\"") && !output.isEmpty()) {
                                    System.out.print(OutputFormatter.convert(output, fmt));
                                    System.out.println();
                                }
                            } catch (Exception ignored) {}
                        }
                        continue;
                    }

                    try {
                        String output = session.execute(trimmed);
                        if (!output.isEmpty()) {
                            System.out.print(OutputFormatter.convert(output, fmt));
                            if (interactive) System.out.println(); // blank line separator
                            System.out.flush();
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
                    description = "Query tokens (e.g. ALL TOP 20 BY retainedSize)")
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

            Path dbPath = outDir.resolve("sql.db");
            SessionDb sessionDb = Files.exists(dbPath) ? SessionDb.open(dbPath) : null;

            String jsonl;
            try {
                jsonl = switch (DslParser.parse(queryStr)) {
                    case DslParser.Invalid e -> {
                        System.err.println("Error: " + e.message());
                        yield null;
                    }
                    case DslParser.Incomplete i -> {
                        System.err.println("Error: incomplete query; expected: "
                            + String.join(", ", i.completions()));
                        yield null;
                    }
                    case DslParser.Complete c -> executeQueryCommand(c.action(), heap, reg, sessionDb);
                };
            } finally {
                if (sessionDb != null) sessionDb.close();
            }

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

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static String toJsonLine(JsonNode node) {
            try {
                return MAPPER.writeValueAsString(node) + "\n";
            } catch (JsonProcessingException e) {
                throw new AssertionError("unreachable", e);
            }
        }

        private static String executeQueryCommand(DslParser.Query q,
                                                   UnpackedHeap heap,
                                                   IndexRegistry reg,
                                                   SessionDb sessionDb) throws IOException, SQLException {
            return switch (q) {
                case DslParser.StatusQuery ignored ->
                    // objectCount includes null sentinel at dense ID 0; display real count
                    toJsonLine(MAPPER.createObjectNode()
                        .put("objectCount", heap.objectCount() - 1)
                        .put("classCount", heap.classCount()));

                case DslParser.ReadQuery rq -> {
                    String content = QueryEngine.readFull(heap, reg, rq.denseId());
                    yield JsonlFormatter.formatRead(rq.denseId(), content);
                }

                case DslParser.ClassesQuery cq ->
                    JsonlFormatter.formatClasses(QueryEngine.classes(heap, reg, cq.glob()));

                case DslParser.ExplainQuery eq ->
                    JsonlFormatter.formatExplain(QueryEngine.explain(heap, reg, eq.denseId()));

                case DslParser.InspectQuery iq ->
                    JsonlFormatter.formatInspect(QueryEngine.inspect(heap, reg, iq.denseId()));

                case DslParser.Pipeline p -> executePipelineQuery(p, heap, reg, sessionDb);

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
                                                    IndexRegistry reg,
                                                    SessionDb sessionDb) throws IOException, SQLException {
            // Resolve source
            BitSet b;
            String contextClass = null;
            switch (p.source()) {
                case DslParser.ClassSource cs -> {
                    b = QueryEngine.buildBitSet(heap, reg, cs.className());
                    contextClass = cs.className().equals("*") ? null : cs.className();
                }
                case DslParser.NameSource ns -> {
                    b = resolveNameOrError(ns.name(), heap, reg, sessionDb);
                    if (b == null) return null;
                }
                case DslParser.ThatSource ignored -> {
                    System.err.println("Error: THAT requires a session — use 'heapo open'");
                    return null;
                }
            }

            for (var filter : p.filters()) {
                switch (filter) {
                    case DslParser.OfTypeFilter f -> {
                        contextClass = f.className();
                        BitSet typeBits = QueryEngine.buildOfTypeBitSet(heap, reg, f.className(), f.exactly());
                        b.and(typeBits);
                    }
                    case DslParser.RetainingFilter f -> {
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
                    }
                    case DslParser.SizedFilter f -> {
                        BitSet result = new BitSet(heap.objectCount());
                        try (var ss = reg.openShallowSize()) {
                            for (int v = b.nextSetBit(0); v >= 0; v = b.nextSetBit(v + 1)) {
                                long size = (long) ss.readInt(v) * 8L;
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
                    }
                    case DslParser.InFilter f -> {
                        BitSet other = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (other == null) return null;
                        b.and(other);
                    }
                    case DslParser.NotInFilter f -> {
                        BitSet other = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (other == null) return null;
                        b.andNot(other);
                    }
                    case DslParser.RetainedByFilter f -> {
                        BitSet retainerBits = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (retainerBits == null) return null;
                        b.and(QueryEngine.buildRetainedByBitSet(heap, reg, retainerBits));
                    }
                    case DslParser.ReferencingFilter f -> {
                        BitSet targetBits = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (targetBits == null) return null;
                        b.and(QueryEngine.buildReferencingBitSet(heap, reg, targetBits));
                    }
                    case DslParser.ReferencedByFilter f -> {
                        BitSet sourceBits = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (sourceBits == null) return null;
                        b.and(QueryEngine.buildReferencedByBitSet(heap, reg, sourceBits));
                    }
                    case DslParser.ReachableFromFilter f -> {
                        BitSet seedBits = resolveNameOrError(f.name(), heap, reg, sessionDb);
                        if (seedBits == null) return null;
                        b.and(QueryEngine.buildReachableFromBitSet(heap, reg, seedBits));
                    }
                    case DslParser.WhereFilter f -> {
                        if (contextClass != null) {
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
                    case DslParser.WhereStringFilter f -> {
                        if (contextClass != null) {
                            var nameIdx = ClassNameIndex.load(heap);
                            int cid = nameIdx.resolve(contextClass);
                            if (cid >= 0) {
                                b = QueryEngine.buildWhereStringFilterBitSet(heap, reg, b, cid,
                                        f.field(), f.value(),
                                        f.leadingStar(), f.trailingStar());
                            }
                        }
                    }
                }
            }

            return switch (p.terminal()) {
                case null ->
                    JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, 10));
                case DslParser.TopNTerminal t ->
                    JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, t.n()));
                case DslParser.ShowNTerminal t ->
                    JsonlFormatter.formatTopN(QueryEngine.topNFromBitSet(heap, reg, b, t.n()));
                case DslParser.SampleNTerminal t ->
                    JsonlFormatter.formatTopN(QueryEngine.sampleFromBitSet(heap, reg, b, t.n()));
                case DslParser.AggregateCountTerminal ignored ->
                    toJsonLine(MAPPER.createObjectNode().put("count", b.cardinality()));
                case DslParser.AggregateRetainedSizeTerminal t ->
                    JsonlFormatter.formatAggregateRetainedSize("(pipeline)", t.func(),
                        QueryEngine.aggregateFromBitSet(heap, reg, b, t.func()));
            };
        }

        private static BitSet resolveNameOrError(String name, UnpackedHeap heap,
                                                   IndexRegistry reg,
                                                   SessionDb sessionDb) throws IOException, SQLException {
            if (name.equalsIgnoreCase("THAT")) {
                System.err.println("Error: THAT requires a session — use 'heapo open'");
                return null;
            }

            BitSet bits = QueryEngine.buildBuiltinBitSet(heap, reg, name);
            if (bits != null) return bits;

            if (sessionDb != null) {
                var histIdOpt = sessionDb.names().resolve(name);
                if (histIdOpt.isPresent()) {
                    var entry = sessionDb.history().findById(histIdOpt.get());
                    if (entry.isPresent()) {
                        if (entry.get().bitsetFile() != null) {
                            return loadBitSet(heap.bitsetsDir().resolve(entry.get().bitsetFile()));
                        }
                        if (entry.get().sqlTable() != null) {
                            System.err.println("Error: '" + name
                                + "' is a table result, not a bitset — query it with SQL SELECT");
                            return null;
                        }
                        System.err.println("Error: '" + name
                            + "' was not persisted. Use CALL THAT <name> in an interactive session first.");
                        return null;
                    }
                }
            }

            System.err.println(sessionDb == null
                ? "Error: '" + name + "' requires a session — use 'heapo open'"
                : "Error: unknown name '" + name + "'");
            return null;
        }

        private static BitSet loadBitSet(Path path) throws IOException {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            long[] words = new long[bytes.length / 8];
            buf.asLongBuffer().get(words);
            return BitSet.valueOf(words);
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

            var progress = timedProgress();
            UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir, progress);
            var reg = new IndexRegistry(heap);
            reg.buildAll(progress);
            progress.accept("Done.");

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
