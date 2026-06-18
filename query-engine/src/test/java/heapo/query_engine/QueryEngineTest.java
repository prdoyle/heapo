package heapo.query_engine;

import heapo.indexes.IndexRegistry;
import heapo.model.TopNRow;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {

    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir", "build/hprof-samples"));

    static UnpackedHeap knownHeap;
    static IndexRegistry knownReg;

    @BeforeAll
    static void setup() throws Exception {
        knownHeap = Unpacker.unpack(samplesDir.resolve("known-objects.hprof"),
                                    Files.createTempDirectory("heapo-qe-known"));
        knownReg  = new IndexRegistry(knownHeap);
        knownReg.buildAll();
    }

    @Test
    void parserAcceptsAllTopBySyntax() {
        var q = DslParser.parse("ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
        assertInstanceOf(DslParser.AllTopByRetainedSize.class, q);
        var atq = (DslParser.AllTopByRetainedSize) q;
        assertEquals("heapo.samples.KnownObjects$Bar", atq.className());
        assertEquals(1, atq.n());
    }

    @Test
    void parserRejectsUnknownSyntax() {
        assertThrows(IllegalArgumentException.class,
            () -> DslParser.parse("SELECT * FROM foo"));
    }

    @Test
    void allBarTop1ReturnsSingleRow() throws Exception {
        List<TopNRow> rows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Bar", 1);

        assertEquals(1, rows.size(), "Expected exactly 1 result");
        var row = rows.get(0);
        assertEquals(0, row.rank());
        assertEquals("heapo.samples.KnownObjects$Bar", row.className());
        assertTrue(row.retainedSize() > 0, "Retained size must be positive");
    }

    @Test
    void allBarTop1RetainedSizeIncludesFooObjects() throws Exception {
        // bar1 → foo1 → foo2, so bar1 retains foo1 and foo2
        List<TopNRow> barRows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Bar", 1);
        List<TopNRow> fooRows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Foo", 10);

        assertFalse(barRows.isEmpty(), "Bar query must return a result");
        long barRetained = barRows.get(0).retainedSize();
        long fooMax      = fooRows.stream().mapToLong(TopNRow::retainedSize).max().orElse(0);
        assertTrue(barRetained >= fooMax,
            "bar retained (" + barRetained + ") should be >= any single foo retained (" + fooMax + ")");
    }

    @Test
    void jsonlFormatterProducesValidLines() throws Exception {
        List<TopNRow> rows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Bar", 1);
        String jsonl = JsonlFormatter.format(rows);
        assertTrue(jsonl.contains("\"rank\":0"), "JSONL must contain rank");
        assertTrue(jsonl.contains("\"type\":\"heapo.samples.KnownObjects$Bar\""), "JSONL must contain type");
        assertTrue(jsonl.contains("\"retainedSize\":"), "JSONL must contain retainedSize");
        assertTrue(jsonl.contains("\"shallowSize\":"), "JSONL must contain shallowSize");
        assertTrue(jsonl.contains("\"id\":\"#"), "JSONL must contain id");
    }

    @Test
    void allStarTop5ReturnsUpToFiveResults() throws Exception {
        List<TopNRow> rows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "*", 5);
        assertTrue(rows.size() <= 5 && rows.size() > 0, "Expected 1-5 results");
        // Verify ranks are in order
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).rank());
        }
    }
}
