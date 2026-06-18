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
        System.exit(new CommandLine(new Main()).execute(args));
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

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
                    if (trimmed.isEmpty()) continue;

                    try {
                        String output = session.execute(trimmed);
                        if (!output.isEmpty()) System.out.print(output);
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
                description = "Output format: jsonl (default), json, human",
                defaultValue = "jsonl")
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
