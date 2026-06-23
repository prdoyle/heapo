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

    // ── Parser tests ──────────────────────────────────────────────────────────

    @Test
    void parserAcceptsClassTopBySyntax() {
        var result = DslParser.parse("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
        assertInstanceOf(DslParser.Complete.class, result);
        var action = ((DslParser.Complete) result).action();
        assertInstanceOf(DslParser.Pipeline.class, action);
        var p = (DslParser.Pipeline) action;
        assertEquals("heapo.samples.KnownObjects$Bar",
            ((DslParser.ClassSource) p.source()).className());
        assertInstanceOf(DslParser.TopNTerminal.class, p.terminal());
        assertEquals(1, ((DslParser.TopNTerminal) p.terminal()).n());
    }

    @Test
    void parserRejectsUnknownSyntax() {
        var result = DslParser.parse("SELECT * FROM foo");
        assertInstanceOf(DslParser.Invalid.class, result);
    }

    @Test
    void parserAggregateCount() {
        var result = DslParser.parse("CLASS heapo.samples.Foo COUNT");
        assertInstanceOf(DslParser.Complete.class, result);
        var action = ((DslParser.Complete) result).action();
        assertInstanceOf(DslParser.Pipeline.class, action);
        var p = (DslParser.Pipeline) action;
        assertEquals("heapo.samples.Foo", ((DslParser.ClassSource) p.source()).className());
        assertInstanceOf(DslParser.AggregateCountTerminal.class, p.terminal());
    }

    @Test
    void parserClassesRequiresGlob() {
        assertInstanceOf(DslParser.Incomplete.class, DslParser.parse("CLASSES"));
    }

    @Test
    void parserClassesStar() {
        var result = DslParser.parse("CLASSES *");
        assertInstanceOf(DslParser.Complete.class, result);
        var action = ((DslParser.Complete) result).action();
        assertInstanceOf(DslParser.ClassesQuery.class, action);
        assertEquals("*", ((DslParser.ClassesQuery) action).glob());
    }

    @Test
    void parserClassesGlob() {
        var result = DslParser.parse("CLASSES heapo.*");
        assertInstanceOf(DslParser.Complete.class, result);
        var action = ((DslParser.Complete) result).action();
        assertInstanceOf(DslParser.ClassesQuery.class, action);
        assertEquals("heapo.*", ((DslParser.ClassesQuery) action).glob());
    }

    @Test
    void parserExplain() {
        var result = DslParser.parse("EXPLAIN i42");
        assertInstanceOf(DslParser.Complete.class, result);
        var action = ((DslParser.Complete) result).action();
        assertInstanceOf(DslParser.ExplainQuery.class, action);
        assertEquals(42, ((DslParser.ExplainQuery) action).denseId());
    }

    @Test
    void parserStatus() {
        var result = DslParser.parse("STATUS");
        assertInstanceOf(DslParser.Complete.class, result);
        assertInstanceOf(DslParser.StatusQuery.class, ((DslParser.Complete) result).action());
    }

    @Test
    void parserEmptyReturnsIncomplete() {
        var result = DslParser.parse("");
        assertInstanceOf(DslParser.Incomplete.class, result);
        assertFalse(((DslParser.Incomplete) result).completions().isEmpty());
    }

    @Test
    void parserIncompleteClassReturnsCompletions() {
        var result = DslParser.parse("CLASS");
        assertInstanceOf(DslParser.Incomplete.class, result);
    }

    @Test
    void parserPipelineWithFilter() {
        var result = DslParser.parse("CLASS com.example.Foo IN mySet TOP 10");
        assertInstanceOf(DslParser.Complete.class, result);
        var p = (DslParser.Pipeline) ((DslParser.Complete) result).action();
        assertEquals(1, p.filters().size());
        assertInstanceOf(DslParser.InFilter.class, p.filters().get(0));
        assertEquals("mySet", ((DslParser.InFilter) p.filters().get(0)).name());
        assertInstanceOf(DslParser.TopNTerminal.class, p.terminal());
    }

    @Test
    void parserTopWithAllSource() {
        var result = DslParser.parse("ALL TOP 5 BY retainedSize");
        assertInstanceOf(DslParser.Complete.class, result);
        var p = (DslParser.Pipeline) ((DslParser.Complete) result).action();
        assertEquals("All", ((DslParser.NameSource) p.source()).name());
        assertEquals(5, ((DslParser.TopNTerminal) p.terminal()).n());
    }

    @Test
    void parserReferencedByRequiresExplicitSource() {
        var result = DslParser.parse("ALL REFERENCED BY GcRoots");
        assertInstanceOf(DslParser.Complete.class, result);
        var p = (DslParser.Pipeline) ((DslParser.Complete) result).action();
        assertEquals("All", ((DslParser.NameSource) p.source()).name());
        assertInstanceOf(DslParser.ReferencedByFilter.class, p.filters().get(0));
        assertEquals("GcRoots", ((DslParser.ReferencedByFilter) p.filters().get(0)).name());
    }

    @Test
    void parserReferencingRequiresExplicitSource() {
        var result = DslParser.parse("ALL REFERENCING i42");
        assertInstanceOf(DslParser.Complete.class, result);
        var p = (DslParser.Pipeline) ((DslParser.Complete) result).action();
        assertEquals("All", ((DslParser.NameSource) p.source()).name());
        assertInstanceOf(DslParser.ReferencingFilter.class, p.filters().get(0));
        assertEquals("i42", ((DslParser.ReferencingFilter) p.filters().get(0)).name());
    }

    @Test
    void parserReachableFromRequiresExplicitSource() {
        var result = DslParser.parse("ALL REACHABLE FROM GcRoots");
        assertInstanceOf(DslParser.Complete.class, result);
        var p = (DslParser.Pipeline) ((DslParser.Complete) result).action();
        assertEquals("All", ((DslParser.NameSource) p.source()).name());
        assertInstanceOf(DslParser.ReachableFromFilter.class, p.filters().get(0));
    }

    @Test
    void parserSessionCommands() {
        assertInstanceOf(DslParser.StatusQuery.class,
            ((DslParser.Complete) DslParser.parse("STATUS")).action());
        assertInstanceOf(DslParser.NamesQuery.class,
            ((DslParser.Complete) DslParser.parse("NAMES *")).action());
        assertInstanceOf(DslParser.ThatQuery.class,
            ((DslParser.Complete) DslParser.parse("THAT")).action());
        assertInstanceOf(DslParser.UndoQuery.class,
            ((DslParser.Complete) DslParser.parse("UNDO")).action());
        assertInstanceOf(DslParser.ForgetQuery.class,
            ((DslParser.Complete) DslParser.parse("FORGET mySet")).action());
        assertInstanceOf(DslParser.CallThatQuery.class,
            ((DslParser.Complete) DslParser.parse("CALL THAT myName")).action());
        assertInstanceOf(DslParser.HistoryRecallQuery.class,
            ((DslParser.Complete) DslParser.parse("h7")).action());
        assertEquals(7, ((DslParser.HistoryRecallQuery)
            ((DslParser.Complete) DslParser.parse("h7")).action()).histId());
    }

    // ── Engine tests ──────────────────────────────────────────────────────────

    @Test
    void allBarTop1ReturnsSingleRow() throws Exception {
        List<TopNRow> rows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Bar", 1);

        assertEquals(1, rows.size(), "Expected exactly 1 result");
        var row = rows.get(0);
        assertEquals(1, row.rank());
        assertEquals("heapo.samples.KnownObjects$Bar", row.className());
        assertTrue(row.retainedSize() > 0, "Retained size must be positive");
    }

    @Test
    void allBarTop1RetainedSizeIncludesFooObjects() throws Exception {
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
        assertTrue(jsonl.contains("\"rank\":1"), "JSONL must contain rank");
        assertTrue(jsonl.contains("\"type\":\"heapo.samples.KnownObjects$Bar\""), "JSONL must contain type");
        assertTrue(jsonl.contains("\"retainedSize\":"), "JSONL must contain retainedSize");
        assertTrue(jsonl.contains("\"shallowSize\":"), "JSONL must contain shallowSize");
        assertTrue(jsonl.contains("\"id\":\"i"), "JSONL must contain id with i prefix");
    }

    @Test
    void allStarTop5ReturnsUpToFiveResults() throws Exception {
        List<TopNRow> rows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "*", 5);
        assertTrue(rows.size() <= 5 && rows.size() > 0, "Expected 1-5 results");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).rank());
        }
    }

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

    @Test
    void explainReturnsPathToRoot() throws Exception {
        var fooRows = QueryEngine.allTopByRetainedSize(
            knownHeap, knownReg, "heapo.samples.KnownObjects$Foo", 1);
        assertFalse(fooRows.isEmpty(), "Should find at least one Foo");
        int fooDenseId = fooRows.get(0).denseId();

        var path = QueryEngine.explain(knownHeap, knownReg, fooDenseId);
        assertFalse(path.isEmpty(), "Explain path should not be empty");
        assertEquals(fooDenseId, path.get(0).denseId(), "First node should be the queried object");
        assertEquals(0, path.get(0).depth(), "Root depth should be 0");
        for (int i = 0; i + 1 < path.size(); i++) {
            assertEquals(i + 1, path.get(i + 1).depth());
        }
    }
}
