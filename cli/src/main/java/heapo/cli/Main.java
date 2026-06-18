package heapo.cli;

import heapo.indexes.IndexRegistry;
import heapo.query_engine.*;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.*;
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

    @Parameters(index = "1..*", description = "Query: ALL <class> TOP <n> BY retainedSize")
    List<String> queryTokens;

    @Option(names = {"-d", "--heap-dir"},
            description = "Directory for unpacked indexes (default: <hprof>.d/ next to the file)")
    Path heapDir;

    @Override
    public Integer call() throws IOException {
        if (queryTokens == null || queryTokens.isEmpty()) {
            System.err.println("Error: no query specified");
            return 1;
        }

        String query = String.join(" ", queryTokens);
        DslParser.Query parsed;
        try {
            parsed = DslParser.parse(query);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        Path outDir = heapDir != null ? heapDir
                    : hprofFile.resolveSibling(hprofFile.getFileName() + ".d");
        Files.createDirectories(outDir);

        UnpackedHeap heap   = Unpacker.unpack(hprofFile, outDir);
        var          reg    = new IndexRegistry(heap);
        reg.buildAll();

        List<QueryEngine.Row> rows = switch (parsed) {
            case DslParser.AllTopByRetainedSize q ->
                QueryEngine.allTopByRetainedSize(heap, reg, q.className(), q.n());
        };

        System.out.print(JsonlFormatter.format(rows));
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
