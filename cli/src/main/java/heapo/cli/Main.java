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
import java.nio.file.*;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name        = "heapo",
    description = "Heap dump analysis via dominator tree",
    mixinStandardHelpOptions = true,
    version     = "0.1.0"
)
public final class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the HPROF file")
    Path hprofFile;

    @Parameters(index = "1..*", arity = "0..*",
                description = "Query tokens: ALL <class> TOP <n> BY retainedSize")
    List<String> queryTokens;

    @Option(names = {"-d", "--heap-dir"},
            description = "Directory for unpacked indexes (default: <hprof>.d/ next to the file)")
    Path heapDir;

    @Option(names = {"--explore"},
            description = "Build all indexes then start interactive REPL")
    boolean explore;

    @Option(names = {"--quick"},
            description = "Run a single query and exit (same as passing query as positional args)")
    String quickQuery;

    @Override
    public Integer call() throws IOException, SQLException {
        Path outDir = heapDir != null ? heapDir
                    : hprofFile.resolveSibling(hprofFile.getFileName() + ".d");
        Files.createDirectories(outDir);

        UnpackedHeap heap = Unpacker.unpack(hprofFile, outDir);
        var          reg  = new IndexRegistry(heap);

        if (explore) {
            reg.buildAll();
            return runRepl(heap, reg, outDir);
        }

        // Single-query mode: tokens on the command line or --quick flag
        String queryStr = quickQuery != null ? quickQuery
                        : (queryTokens != null ? String.join(" ", queryTokens) : "");

        if (queryStr.isBlank()) {
            System.err.println("Error: specify a query or use --explore");
            return 1;
        }

        DslParser.Query parsed;
        try {
            parsed = DslParser.parse(queryStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        String output = switch (parsed) {
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

        System.out.print(output);
        return 0;
    }

    private int runRepl(UnpackedHeap heap, IndexRegistry reg, Path outDir) throws IOException, SQLException {
        Path dbPath = outDir.resolve("sql.db");
        try (var terminal = TerminalBuilder.builder().build();
             var session  = new HeapSession(heap, reg, dbPath)) {

            var reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
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

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
