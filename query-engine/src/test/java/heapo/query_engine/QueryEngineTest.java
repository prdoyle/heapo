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
        String jsonl = JsonlFormatter.formatTopN(rows);
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

    // ── Phase 6: AGGREGATE COUNT ──────────────────────────────────────────────

    @Test
    void aggregateCountFooReturnsExpectedCount() throws Exception {
        long count = QueryEngine.aggregateCount(knownHeap, knownReg,
            "heapo.samples.KnownObjects$Foo");
        assertTrue(count >= 2, "Expected >= 2 Foo instances; got " + count);
    }

    @Test
    void aggregateCountStarReturnsTotalObjects() throws Exception {
        long count = QueryEngine.aggregateCount(knownHeap, knownReg, "*");
        assertEquals(knownHeap.objectCount(), count, "ALL * count should equal objectCount");
    }

    @Test
    void aggregateCountMissingClassReturnsZero() throws Exception {
        long count = QueryEngine.aggregateCount(knownHeap, knownReg,
            "heapo.samples.NonExistentClass");
        assertEquals(0, count);
    }

    // ── Phase 6: CLASSES ──────────────────────────────────────────────────────

    @Test
    void classesListsAllClasses() throws Exception {
        var classes = QueryEngine.classes(knownHeap, knownReg, null);
        assertFalse(classes.isEmpty(), "Should find at least some classes");
        boolean hasFoo = classes.stream()
            .anyMatch(c -> c.className().contains("KnownObjects$Foo"));
        assertTrue(hasFoo, "Should list KnownObjects$Foo");
    }

    @Test
    void classesGlobFilter() throws Exception {
        var classes = QueryEngine.classes(knownHeap, knownReg, "heapo.samples.*");
        assertFalse(classes.isEmpty(), "Glob should match at least one class");
        for (var c : classes) {
            assertTrue(c.className().startsWith("heapo.samples."),
                "All results should match glob; got " + c.className());
        }
    }

    @Test
    void classesAreOrderedByInstanceCountDescending() throws Exception {
        var classes = QueryEngine.classes(knownHeap, knownReg, null);
        for (int i = 0; i + 1 < classes.size(); i++) {
            assertTrue(classes.get(i).instanceCount() >= classes.get(i + 1).instanceCount(),
                "Should be sorted descending by instance count");
        }
    }

    // ── Phase 6: EXPLAIN ─────────────────────────────────────────────────────

    @Test
    void explainReturnsPathToRoot() throws Exception {
        // Find a Foo instance
        var fooRows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Foo", 1);
        assertFalse(fooRows.isEmpty(), "Should find at least one Foo");
        int fooDenseId = fooRows.get(0).denseId();

        var path = QueryEngine.explain(knownHeap, knownReg, fooDenseId);
        assertFalse(path.isEmpty(), "Explain path should not be empty");
        assertEquals(fooDenseId, path.get(0).denseId(), "First node should be the queried object");
        assertEquals(0, path.get(0).depth(), "Root depth should be 0");
        // Depths should be monotonically increasing
        for (int i = 0; i + 1 < path.size(); i++) {
            assertEquals(i + 1, path.get(i + 1).depth());
        }
    }

    // ── Phase 6: DslParser extensions ────────────────────────────────────────

    @Test
    void parserAggregateCount() {
        var q = DslParser.parse("ALL heapo.samples.Foo AGGREGATE COUNT");
        assertInstanceOf(DslParser.AggregateCount.class, q);
        assertEquals("heapo.samples.Foo", ((DslParser.AggregateCount) q).className());
    }

    @Test
    void parserClassesAll() {
        var q = DslParser.parse("CLASSES");
        assertInstanceOf(DslParser.ClassesQuery.class, q);
        assertNull(((DslParser.ClassesQuery) q).glob());
    }

    @Test
    void parserClassesMatching() {
        var q = DslParser.parse("CLASSES MATCHING heapo.*");
        assertInstanceOf(DslParser.ClassesQuery.class, q);
        assertEquals("heapo.*", ((DslParser.ClassesQuery) q).glob());
    }

    @Test
    void parserExplain() {
        var q = DslParser.parse("EXPLAIN #42");
        assertInstanceOf(DslParser.ExplainQuery.class, q);
        assertEquals(42, ((DslParser.ExplainQuery) q).denseId());
    }

    @Test
    void parserStatus() {
        var q = DslParser.parse("STATUS");
        assertInstanceOf(DslParser.StatusQuery.class, q);
    }
}
