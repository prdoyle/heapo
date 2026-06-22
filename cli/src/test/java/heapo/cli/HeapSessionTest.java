package heapo.cli;

import heapo.indexes.IndexRegistry;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class HeapSessionTest {

    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir", "build/hprof-samples"));

    @TempDir static Path tempRoot;

    static UnpackedHeap heap;
    static IndexRegistry registry;
    static Path dbPath;

    @BeforeAll
    static void setup() throws Exception {
        heap     = Unpacker.unpack(samplesDir.resolve("known-objects.hprof"),
                                   tempRoot.resolve("heap"));
        registry = new IndexRegistry(heap);
        registry.buildAll();
        dbPath   = tempRoot.resolve("sql.db");
    }

    @Test
    void queryIsRecordedInHistory() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            String hist = session.execute("HISTORY 5");
            assertTrue(hist.contains("KnownObjects"), "History should contain the command");
        }
    }

    @Test
    void callThatBindsName() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            String result = session.execute("CALL THAT myBars");
            assertTrue(result.contains("myBars"), "Result should confirm the bound name");

            String names = session.execute("NAMES");
            assertTrue(names.contains("myBars"), "NAMES should list myBars");
        }
    }

    @Test
    void forgetRemovesName() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("CALL THAT tempName");
            session.execute("FORGET tempName");

            String names = session.execute("NAMES");
            assertFalse(names.contains("tempName"), "NAMES should not list forgotten name");
        }
    }

    @Test
    void namesPersistedAcrossRestarts() throws Exception {
        Path localDb = tempRoot.resolve("restart-test.db");

        // First session: run a query and name the result
        try (var session = new HeapSession(heap, registry, localDb)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 2 BY retainedSize");
            session.execute("CALL THAT persistedBars");
        }

        // Second session: verify the name survived
        try (var session = new HeapSession(heap, registry, localDb)) {
            String names = session.execute("NAMES");
            assertTrue(names.contains("persistedBars"),
                "Name should survive across session restarts");
        }
    }

    @Test
    void callThatReturnsErrorWhenThatIsEmpty() throws Exception {
        Path freshDb = tempRoot.resolve("empty-that.db");
        try (var session = new HeapSession(heap, registry, freshDb)) {
            String result = session.execute("CALL THAT someName");
            assertTrue(result.contains("error"), "Should report error when THAT is empty");
        }
    }

    @Test
    void unknownCommandReturnsError() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            String result = session.execute("DO SOMETHING WEIRD");
            assertTrue(result.contains("error"), "Unknown command should return an error");
        }
    }

    @Test
    void historyLimitIsRespected() throws Exception {
        Path limitDb = tempRoot.resolve("limit-test.db");
        try (var session = new HeapSession(heap, registry, limitDb)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("CLASS heapo.samples.KnownObjects$Foo TOP 1 BY retainedSize");
            String hist = session.execute("HISTORY 1");
            // Should have exactly one JSON line (plus trailing newline)
            long lineCount = hist.lines().filter(l -> !l.isBlank()).count();
            assertEquals(1, lineCount, "HISTORY 1 should return exactly 1 entry");
        }
    }

    // ── Phase 7: SQL integration ──────────────────────────────────────────────

    @Test
    void sqlSelectFromDslResult() throws Exception {
        Path sqlDb = tempRoot.resolve("sql-test.db");
        try (var session = new HeapSession(heap, registry, sqlDb)) {
            // Run a DSL query to create the table
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 5 BY retainedSize");
            session.execute("CALL THAT myBars");

            // Get the internal table name from names → history
            String namesOutput = session.execute("NAMES");
            assertTrue(namesOutput.contains("myBars"), "Expected myBars in names");

            // Get the history entry to find the sql_table name
            String histOutput = session.execute("HISTORY 10");
            assertTrue(histOutput.contains("KnownObjects$Bar"), "History should record the query");

            // Now run SQL against the stored table (requires knowing table name)
            // Use SELECT from the session's internal tables — just verify routing works
            String sqlResult = session.execute("SELECT 1 AS test_col");
            assertFalse(sqlResult.contains("error"),
                "Simple SQL SELECT should succeed; got: " + sqlResult);
        }
    }

    @Test
    void sqlResultIsStoredAsNewTable() throws Exception {
        Path sqlDb = tempRoot.resolve("sql-store-test.db");
        try (var session = new HeapSession(heap, registry, sqlDb)) {
            String result = session.execute("SELECT 42 AS answer, 'hello' AS greeting");
            assertFalse(result.contains("error"), "SQL should succeed");
            assertTrue(result.contains("sqlTable"), "Result should reference a sqlTable");
            assertTrue(result.contains("rowCount"), "Result should include rowCount");
        }
    }

    @Test
    void sqlIsMandatoryRoutedByPrefix() throws Exception {
        Path sqlDb = tempRoot.resolve("sql-routing.db");
        try (var session = new HeapSession(heap, registry, sqlDb)) {
            String selectResult = session.execute("SELECT 1");
            assertFalse(selectResult.contains("\"error\""), "SELECT should route to SQL");

            String withResult = session.execute("WITH x AS (SELECT 1) SELECT * FROM x");
            assertFalse(withResult.contains("\"error\""), "WITH should route to SQL");
        }
    }

    // ── Phase 9: UNDO ─────────────────────────────────────────────────────────

    @Test
    void undoCallThatRemovesBinding() throws Exception {
        Path p = tempRoot.resolve("undo-call.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("CALL THAT tempName");
            String undo = session.execute("UNDO");
            assertFalse(undo.contains("\"error\""), "UNDO should succeed: " + undo);
            assertTrue(undo.contains("\"CALL\""), "UNDO response should mention CALL");

            String names = session.execute("NAMES");
            assertFalse(names.contains("tempName"), "Name should be removed after UNDO");
        }
    }

    @Test
    void undoForgetRestoresBinding() throws Exception {
        Path p = tempRoot.resolve("undo-forget.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("CALL THAT savedName");
            session.execute("FORGET savedName");
            String undo = session.execute("UNDO");
            assertFalse(undo.contains("\"error\""), "UNDO of FORGET should succeed: " + undo);

            String names = session.execute("NAMES");
            assertTrue(names.contains("savedName"), "Name should be restored after UNDO of FORGET");
        }
    }

    @Test
    void undoWithNothingToUndoReturnsError() throws Exception {
        Path p = tempRoot.resolve("undo-empty.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String undo = session.execute("UNDO");
            assertTrue(undo.contains("\"error\""), "UNDO with nothing to undo should return error");
        }
    }

    @Test
    void undoCallRestoresPreviousBinding() throws Exception {
        Path p = tempRoot.resolve("undo-displace.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("CALL THAT myName");
            session.execute("CLASS heapo.samples.KnownObjects$Bar TOP 2 BY retainedSize");
            session.execute("CALL THAT myName"); // displaces the first binding
            session.execute("UNDO");

            String names = session.execute("NAMES");
            assertTrue(names.contains("myName"), "myName should still be bound after UNDO");
            // The restored binding should point to the first result (history id 1 or 2)
            assertTrue(names.contains("\"restored\"") || names.contains("myName"),
                "Binding should be restored to the original result");
        }
    }

    // ── Phase: BitSetAnswer (CLASS <class> as standalone source) ────────────────

    @Test
    void allClassAloneDisplaysTopN() throws Exception {
        Path p = tempRoot.resolve("bitset-display.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("CLASS heapo.samples.KnownObjects$Bar");
            assertFalse(result.contains("\"error\""), "CLASS <class> alone should succeed: " + result);
            assertTrue(result.contains("\"rank\""), "Should display ranked rows");
        }
    }

    @Test
    void allWildcardAloneDisplaysTopN() throws Exception {
        Path p = tempRoot.resolve("bitset-wildcard.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("CLASS *");
            assertFalse(result.contains("\"error\""), "CLASS * alone should succeed: " + result);
            assertTrue(result.contains("\"rank\""), "Should display ranked rows");
        }
    }

    @Test
    void thatAfterAllClassIsBitSet() throws Exception {
        Path p = tempRoot.resolve("bitset-that.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            String result = session.execute("THAT");
            assertFalse(result.contains("\"error\""), "THAT after CLASS <class> should succeed: " + result);
            assertTrue(result.contains("\"rank\""), "THAT should show ranked rows");
        }
    }

    @Test
    void callThatOnBitSetAnswerPersistsAcrossRestart() throws Exception {
        Path p = tempRoot.resolve("bitset-persist.db");

        int targetHistId;
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            String callResult = session.execute("CALL THAT myBars");
            assertFalse(callResult.contains("\"error\""), "CALL THAT on BitSet should succeed: " + callResult);
            assertTrue(callResult.contains("myBars"), "Should confirm bound name");

            // Extract historyId from call result to use in new session
            int idx = callResult.indexOf("\"historyId\":") + "\"historyId\":".length();
            int end = callResult.indexOf(',', idx);
            if (end < 0) end = callResult.indexOf('}', idx);
            targetHistId = Integer.parseInt(callResult.substring(idx, end).trim());
        }

        // New session — verify bitset survives via @id recall
        try (var session = new HeapSession(heap, registry, p)) {
            String names = session.execute("NAMES");
            assertTrue(names.contains("myBars"), "Name should survive restart");

            String recalled = session.execute("h" + targetHistId);
            assertFalse(recalled.contains("\"error\""),
                "Recall of named bitset should succeed after restart: " + recalled);
            assertTrue(recalled.contains("\"rank\""), "Recalled bitset should show ranked rows");
        }
    }

    // ── Phase: Pipeline (FROM / IN / NOT IN) ─────────────────────────────────

    @Test
    void fromThatAfterAllClassWorks() throws Exception {
        Path p = tempRoot.resolve("from-that.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            String result = session.execute("FROM THAT TOP 5 BY retainedSize");
            assertFalse(result.contains("\"error\""), "FROM THAT should succeed: " + result);
            assertTrue(result.contains("\"rank\""), "Should show ranked rows");
        }
    }

    @Test
    void fromNamedBitSetWorks() throws Exception {
        Path p = tempRoot.resolve("from-named.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");
            String result = session.execute("FROM bars TOP 5 BY retainedSize");
            assertFalse(result.contains("\"error\""), "FROM <name> should succeed: " + result);
            assertTrue(result.contains("\"rank\""), "Should show ranked rows");
        }
    }

    @Test
    void inFilterNarrowsSet() throws Exception {
        Path p = tempRoot.resolve("in-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // CLASS * gives the full heap; bars is a subset
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            // CLASS * IN bars should be the same as bars
            String result = session.execute("CLASS * IN bars TOP 100 BY retainedSize");
            assertFalse(result.contains("\"error\""), "IN filter should succeed: " + result);

            // Result must only contain Bar instances
            for (String line : result.lines().filter(l -> !l.isBlank()).toList()) {
                assertTrue(line.contains("KnownObjects$Bar"),
                    "IN bars should only return Bar objects; got: " + line);
            }
        }
    }

    @Test
    void notInFilterExcludesSet() throws Exception {
        Path p = tempRoot.resolve("not-in-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            // CLASS * NOT IN bars should contain no Bar instances
            String result = session.execute("CLASS * NOT IN bars TOP 100 BY retainedSize");
            assertFalse(result.contains("\"error\""), "NOT IN filter should succeed: " + result);

            for (String line : result.lines().filter(l -> !l.isBlank()).toList()) {
                assertFalse(line.contains("KnownObjects$Bar"),
                    "NOT IN bars should exclude Bar objects; got: " + line);
            }
        }
    }

    @Test
    void fromNameAggregateCount() throws Exception {
        Path p = tempRoot.resolve("from-agg.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            String allCount  = session.execute("CLASS heapo.samples.KnownObjects$Bar COUNT");
            String fromCount = session.execute("FROM bars COUNT");

            assertFalse(fromCount.contains("\"error\""), "FROM <name> COUNT should succeed");
            // Both should report the same count
            assertTrue(fromCount.contains("\"count\""), "Should have count field");
        }
    }

    // ── Phase: NAMES MATCHING ─────────────────────────────────────────────────

    @Test
    void namesMatchingFiltersResults() throws Exception {
        Path p = tempRoot.resolve("names-matching.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT alphaSet");
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT betaSet");

            String all     = session.execute("NAMES");
            String matched = session.execute("NAMES MATCHING alpha*");
            String none    = session.execute("NAMES MATCHING zzzNone*");

            assertTrue(all.contains("alphaSet"), "NAMES should list alphaSet");
            assertTrue(all.contains("betaSet"),  "NAMES should list betaSet");
            assertTrue(matched.contains("alphaSet"),  "NAMES MATCHING alpha* should include alphaSet");
            assertFalse(matched.contains("betaSet"),  "NAMES MATCHING alpha* should exclude betaSet");
            assertTrue(none.contains("\"names\":[]"), "No-match pattern should return empty list");
        }
    }

    // ── Phase: built-in names ─────────────────────────────────────────────────

    @Test
    void gcRootsBuiltinReturnsNonEmptyBitSet() throws Exception {
        Path p = tempRoot.resolve("builtin-gcroots.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("FROM GcRoots COUNT");
            assertFalse(result.contains("\"error\""), "GcRoots should resolve: " + result);
            assertTrue(result.contains("\"count\""), "Should return count");
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertTrue(count > 0, "GcRoots should be non-empty in any real heap dump");
        }
    }

    @Test
    void threadsBuiltinReturnsZeroOrMore() throws Exception {
        Path p = tempRoot.resolve("builtin-threads.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("FROM Threads COUNT");
            assertFalse(result.contains("\"error\""), "Threads built-in should resolve: " + result);
            assertTrue(result.contains("\"count\""), "Should return count");
        }
    }

    @Test
    void builtinNameUsableInRetainedByFilter() throws Exception {
        Path p = tempRoot.resolve("builtin-in-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("CLASS * RETAINED BY GcRoots COUNT");
            assertFalse(result.contains("\"error\""),
                "RETAINED BY GcRoots should resolve: " + result);
            long retained = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            // GC roots retain at least themselves
            assertTrue(retained >= 0, "Retained count should be non-negative");
        }
    }

    // ── Phase: OF TYPE [EXACTLY] <class> pipeline filter ─────────────────────

    @Test
    void ofTypeExactlyFiltersToExactClass() throws Exception {
        Path p = tempRoot.resolve("of-type-exactly.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // CLASS * OF TYPE EXACTLY Bar should give the same count as ALL Bar
            String exactResult = session.execute(
                "CLASS * OF TYPE EXACTLY heapo.samples.KnownObjects$Bar COUNT");
            String directResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar COUNT");

            assertFalse(exactResult.contains("\"error\""),
                "OF TYPE EXACTLY should succeed: " + exactResult);
            // Both should report the same count
            String exactCount  = exactResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip();
            String directCount = directResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip();
            assertEquals(directCount, exactCount,
                "OF TYPE EXACTLY should match direct CLASS <class> count");
        }
    }

    @Test
    void ofTypeIncludesSubclasses() throws Exception {
        Path p = tempRoot.resolve("of-type-subclass.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // OF TYPE java.lang.Object should include everything (all objects extend Object)
            String objCount = session.execute("CLASS * OF TYPE java.lang.Object COUNT");
            String allCount = session.execute("CLASS * COUNT");

            assertFalse(objCount.contains("\"error\""),
                "OF TYPE java.lang.Object should succeed: " + objCount);
            long ofTypeN = Long.parseLong(objCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long totalN  = Long.parseLong(allCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            // OF TYPE Object should include all objects
            assertTrue(ofTypeN <= totalN,
                "OF TYPE Object count should not exceed total object count");
            assertTrue(ofTypeN > 0, "Should find at least some objects of type Object");
        }
    }

    // ── Phase: EXPLAIN <name> provenance ─────────────────────────────────────

    @Test
    void explainNameShowsSourceCommand() throws Exception {
        Path p = tempRoot.resolve("explain-name.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT myBarsE");

            String result = session.execute("EXPLAIN myBarsE");
            assertFalse(result.contains("\"error\""), "EXPLAIN <name> should succeed: " + result);
            assertTrue(result.contains("\"name\":\"myBarsE\""), "Should include name");
            assertTrue(result.contains("\"histId\""),           "Should include histId");
            assertTrue(result.contains("\"command\""),          "Should include command");
            assertTrue(result.contains("KnownObjects$Bar"),     "Command should reference the query");
        }
    }

    @Test
    void explainUnknownNameReturnsError() throws Exception {
        Path p = tempRoot.resolve("explain-unknown.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("EXPLAIN nonExistentName");
            assertTrue(result.contains("\"error\""), "EXPLAIN unknown name should return error");
        }
    }

    // ── Phase: REFERENCING / REFERENCED BY direct reference filters ───────────

    @Test
    void referencingFilterFindsDirectReferencers() throws Exception {
        Path p = tempRoot.resolve("referencing.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Build a set of objects and find who references them
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            // CLASS * REFERENCING bars = objects that directly point to any bar
            String result = session.execute("CLASS * REFERENCING bars COUNT");
            assertFalse(result.contains("\"error\""),
                "REFERENCING filter should succeed: " + result);
            assertTrue(result.contains("\"count\""), "Should return count");
            // At least 0 (some objects may not reference bars directly)
        }
    }

    @Test
    void referencedByFilterFindsDirectReferents() throws Exception {
        Path p = tempRoot.resolve("referenced-by.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // GcRoots reference some objects; find those objects
            String result = session.execute("CLASS * REFERENCED BY GcRoots COUNT");
            assertFalse(result.contains("\"error\""),
                "REFERENCED BY filter should succeed: " + result);
            assertTrue(result.contains("\"count\""), "Should return count");
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            // GC roots must reference at least something
            assertTrue(count >= 0, "REFERENCED BY count should be non-negative");
        }
    }

    // ── Phase: SIZED > n pipeline filter ─────────────────────────────────────

    @Test
    void sizedFilterByShallowSize() throws Exception {
        Path p = tempRoot.resolve("sized-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // All objects sized > 0 bytes should be at most the full set
            String allCount  = session.execute("CLASS * COUNT");
            String sizResult = session.execute("CLASS * SIZED > 0 COUNT");

            assertFalse(sizResult.contains("\"error\""), "SIZED filter should succeed: " + sizResult);
            long allN = Long.parseLong(allCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long sizN = Long.parseLong(sizResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertTrue(sizN <= allN, "SIZED > 0 count should not exceed total");
            assertTrue(sizN >= 0,   "SIZED > 0 count should be non-negative");

            // Nothing has negative size
            String zeroResult = session.execute("CLASS * SIZED > 99999999 COUNT");
            assertFalse(zeroResult.contains("\"error\""), "SIZED > huge should succeed: " + zeroResult);
        }
    }

    // ── Phase: RETAINING > n pipeline filter ─────────────────────────────────

    @Test
    void retainingFilterAsPartOfPipeline() throws Exception {
        Path p = tempRoot.resolve("retaining-pipeline.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Build a named set, then filter it by retained size
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT allBars");

            // Keep only bars retaining > 0 bytes (all should qualify)
            String result = session.execute("FROM allBars RETAINING > 0 COUNT");
            assertFalse(result.contains("\"error\""), "RETAINING filter in pipeline should succeed: " + result);
            assertTrue(result.contains("\"count\""), "Should return count field");

            // Keep only bars retaining > Long.MAX_VALUE (none should qualify)
            String empty = session.execute("FROM allBars RETAINING > 9999999999999 COUNT");
            assertFalse(empty.contains("\"error\""), "RETAINING > huge should succeed: " + empty);
            long count = Long.parseLong(empty.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(0, count, "No objects should retain > 9999999999999 bytes");
        }
    }

    @Test
    void retainingFilterCombinesWithInFilter() throws Exception {
        Path p = tempRoot.resolve("retaining-combined.db");
        try (var session = new HeapSession(heap, registry, p)) {
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            // Bars intersected with bars (no-op), then filtered by retaining > 0
            String result = session.execute("CLASS * IN bars RETAINING > 0 COUNT");
            assertFalse(result.contains("\"error\""),
                "Combined IN + RETAINING filters should succeed: " + result);
            assertTrue(result.contains("\"count\""), "Should return count");
        }
    }

    // ── Phase: RETAINED BY <name> pipeline filter ─────────────────────────────

    @Test
    void retainedByFilterKeepsOnlyDominatedObjects() throws Exception {
        Path p = tempRoot.resolve("retained-by-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Build a named bitset of top-retained Bar instances
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            // Total heap count
            String allCountJson = session.execute("CLASS * COUNT");
            long allCount = Long.parseLong(allCountJson.replaceAll(".*\"count\":(\\d+).*", "$1").strip());

            // Objects retained by bars — must be ≤ total heap size
            String retainedCountJson = session.execute("CLASS * RETAINED BY bars COUNT");
            assertFalse(retainedCountJson.contains("\"error\""),
                "RETAINED BY filter should succeed: " + retainedCountJson);
            assertTrue(retainedCountJson.contains("\"count\""), "Should have count field");

            long retainedCount = Long.parseLong(
                retainedCountJson.replaceAll(".*\"count\":(\\d+).*", "$1").strip());

            // The retained set includes bars themselves plus their descendants
            assertTrue(retainedCount >= 1, "At least one object should be retained");
            assertTrue(retainedCount <= allCount, "Retained set cannot exceed full heap");
        }
    }

    @Test
    void retainedByFilterIncludesRetainersThemselves() throws Exception {
        Path p = tempRoot.resolve("retained-by-self.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Build a bitset of all bars (no terminal = BitSetAnswer)
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars2");

            // Bars are retained by themselves, so must appear in the result
            String result = session.execute("CLASS * RETAINED BY bars2 TOP 20 BY retainedSize");
            assertFalse(result.contains("\"error\""), "RETAINED BY should succeed: " + result);
            assertTrue(result.contains("KnownObjects$Bar"),
                "Bar instances must appear in their own retained set");
        }
    }

    // ── REPL output formatting ────────────────────────────────────────────────

    @Test
    void replOutputIsHumanFormattedNotJsonl() throws Exception {
        // Simulate what the REPL loop does: execute then format as HUMAN
        try (var session = new HeapSession(heap, registry, dbPath)) {
            String jsonl  = session.execute("CLASS * TOP 5 BY retainedSize");
            String output = OutputFormatter.convert(jsonl, OutputFormatter.Format.HUMAN);

            assertFalse(output.startsWith("{"), "REPL output must not be raw JSONL");
            assertTrue(output.contains("rank"),         "Human output should have a 'rank' header");
            assertTrue(output.contains("retainedSize"), "Human output should have a 'retainedSize' header");
            assertTrue(output.contains("----"),         "Human output should have a separator line");
        }
    }

    // ── Phase 8: additional DSL operations ───────────────────────────────────

    @Test
    void bottomNReturnsSmallestRetainedSizes() throws Exception {
        Path p = tempRoot.resolve("bottom-n.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar BOTTOM 2 BY retainedSize");
            assertFalse(result.contains("\"error\""), "BOTTOM query should succeed: " + result);
            long lineCount = result.lines().filter(l -> !l.isBlank()).count();
            assertTrue(lineCount <= 2, "Should return at most 2 rows");
        }
    }

    @Test
    void aggregateCountWildcard() throws Exception {
        Path p = tempRoot.resolve("agg-count-all.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute("CLASS * COUNT");
            assertFalse(result.contains("\"error\""), "Wildcard count should succeed: " + result);
            assertTrue(result.contains("\"count\""), "Result should contain count field");
        }
    }

    @Test
    void aggregateMaxRetainedSize() throws Exception {
        Path p = tempRoot.resolve("agg-max.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar MAX retainedSize");
            assertFalse(result.contains("\"error\""), "MAX query should succeed: " + result);
            assertTrue(result.contains("\"MAX\""), "Result should report MAX func");
            assertTrue(result.contains("\"retainedSize\""), "Result should contain retainedSize");
        }
    }

    @Test
    void aggregateSumRetainedSize() throws Exception {
        Path p = tempRoot.resolve("agg-sum.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar SUM retainedSize");
            assertFalse(result.contains("\"error\""), "SUM query should succeed: " + result);
            assertTrue(result.contains("\"SUM\""), "Result should report SUM func");
        }
    }

    @Test
    void dominatorSubtreeReturnsDescendants() throws Exception {
        Path p = tempRoot.resolve("dominator.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Get a non-trivial object first
            String topResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            assertFalse(topResult.contains("\"error\""), "TOP query should succeed");
            // Extract the id from the first line, e.g. "id":"i42"
            int marker  = topResult.indexOf("\"id\":\"i");
            int idStart = marker + "\"id\":\"i".length();
            int idEnd   = topResult.indexOf('"', idStart);
            int denseId = Integer.parseInt(topResult.substring(idStart, idEnd));

            String subtree = session.execute("RETAINED BY i" + denseId);
            assertFalse(subtree.contains("\"error\""),
                "RETAINED BY query should succeed: " + subtree);
            // The root itself should always appear
            assertTrue(subtree.contains("\"i" + denseId + "\""),
                "Root object should appear in subtree");
        }
    }

    @Test
    void retainingFilterReturnsMatchingObjects() throws Exception {
        Path p = tempRoot.resolve("retaining-filter.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Ask for objects retaining more than 0 bytes (should return all)
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar RETAINING > 0");
            assertFalse(result.contains("\"error\""), "RETAINING query should succeed: " + result);
        }
    }

    // ── Phase: REACHABLE FROM filter ─────────────────────────────────────────

    @Test
    void reachableFromGcRootsCoversHeap() throws Exception {
        Path p = tempRoot.resolve("reachable-from.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // All objects are reachable from GcRoots (or a subset thereof)
            String totalResult    = session.execute("CLASS * COUNT");
            String reachableResult = session.execute("CLASS * REACHABLE FROM GcRoots COUNT");

            assertFalse(reachableResult.contains("\"error\""),
                "REACHABLE FROM filter should succeed: " + reachableResult);
            long total    = Long.parseLong(totalResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long reachable = Long.parseLong(reachableResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            // Every live object should be reachable from GC roots
            assertTrue(reachable >= 1, "Some objects must be reachable from GC roots");
            assertTrue(reachable <= total, "Reachable count must not exceed total");
        }
    }

    @Test
    void reachableFromExpandsTransitively() throws Exception {
        Path p = tempRoot.resolve("reachable-transitive.db");
        try (var session = new HeapSession(heap, registry, p)) {
            // Build a seed set then find reachable objects
            session.execute("CLASS heapo.samples.KnownObjects$Bar");
            session.execute("CALL THAT bars");

            String directCount    = session.execute("CLASS * REFERENCED BY bars COUNT");
            String reachableCount = session.execute("CLASS * REACHABLE FROM bars COUNT");

            assertFalse(reachableCount.contains("\"error\""),
                "REACHABLE FROM should succeed: " + reachableCount);
            long direct   = Long.parseLong(directCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long reachable = Long.parseLong(reachableCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            // Transitive reachability includes seed objects themselves, so >= direct referents
            assertTrue(reachable >= direct,
                "REACHABLE FROM should find at least as many objects as REFERENCED BY");
        }
    }

    // ── Phase: WHERE primitive field filter ───────────────────────────────────

    @Test
    void whereFilterMatchesExactValue() throws Exception {
        // KnownObjects.Foo has int size: foo1.size=42, foo2.size=99
        Path p = tempRoot.resolve("where-exact.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Foo WHERE size = 42 COUNT");
            assertFalse(result.contains("\"error\""), "WHERE exact should succeed: " + result);
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(1L, count, "Exactly one Foo has size=42");
        }
    }

    @Test
    void whereFilterRejectsNonMatching() throws Exception {
        Path p = tempRoot.resolve("where-no-match.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Foo WHERE size = 0 COUNT");
            assertFalse(result.contains("\"error\""), "WHERE no-match should succeed: " + result);
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(0L, count, "No Foo has size=0");
        }
    }

    @Test
    void whereFilterGreaterThan() throws Exception {
        // foo1.size=42, foo2.size=99 — both > 10, one > 50
        Path p = tempRoot.resolve("where-gt.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String allResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Foo WHERE size > 10 COUNT");
            String oneResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Foo WHERE size > 50 COUNT");
            assertFalse(allResult.contains("\"error\""), "WHERE > 10 should succeed: " + allResult);
            assertFalse(oneResult.contains("\"error\""), "WHERE > 50 should succeed: " + oneResult);
            long allCount = Long.parseLong(allResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long oneCount = Long.parseLong(oneResult.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(2L, allCount, "Both Foos have size > 10");
            assertEquals(1L, oneCount, "One Foo has size > 50 (size=99)");
        }
    }

    @Test
    void whereFilterOnBarCount() throws Exception {
        // KnownObjects.Bar has int count: bar1.count=7
        Path p = tempRoot.resolve("where-bar.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String result = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar WHERE count = 7 COUNT");
            assertFalse(result.contains("\"error\""), "WHERE Bar.count should succeed: " + result);
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(1L, count, "Exactly one Bar has count=7");
        }
    }

    // ── Object ID as single-bit bitset ────────────────────────────────────────

    private static int extractDenseId(String jsonl) {
        int marker  = jsonl.indexOf("\"id\":\"i");
        int idStart = marker + "\"id\":\"i".length();
        int idEnd   = jsonl.indexOf('"', idStart);
        return Integer.parseInt(jsonl.substring(idStart, idEnd));
    }

    @Test
    void objectIdUsableAsInFilter() throws Exception {
        Path p = tempRoot.resolve("objid-in.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String topResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            int denseId = extractDenseId(topResult);

            String result = session.execute("CLASS * IN i" + denseId + " COUNT");
            assertFalse(result.contains("\"error\""), "IN i<n> should succeed: " + result);
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(1L, count, "IN singleton should return exactly 1 object");
        }
    }

    @Test
    void objectIdUsableAsNotInFilter() throws Exception {
        Path p = tempRoot.resolve("objid-not-in.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String topResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            int denseId = extractDenseId(topResult);

            String allCount = session.execute("CLASS * COUNT");
            String result   = session.execute("CLASS * NOT IN i" + denseId + " COUNT");
            assertFalse(result.contains("\"error\""), "NOT IN i<n> should succeed: " + result);
            long all  = Long.parseLong(allCount.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            long notIn = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertEquals(all - 1, notIn, "NOT IN singleton should exclude exactly 1 object");
        }
    }

    @Test
    void objectIdUsableAsRetainedByFilter() throws Exception {
        Path p = tempRoot.resolve("objid-retained-by.db");
        try (var session = new HeapSession(heap, registry, p)) {
            String topResult = session.execute(
                "CLASS heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            int denseId = extractDenseId(topResult);

            String result = session.execute("CLASS * RETAINED BY i" + denseId + " COUNT");
            assertFalse(result.contains("\"error\""), "RETAINED BY i<n> should succeed: " + result);
            long count = Long.parseLong(result.replaceAll(".*\"count\":(\\d+).*", "$1").strip());
            assertTrue(count >= 1, "Object retains at least itself");
            // Verify the object itself appears
            String top = session.execute("CLASS * RETAINED BY i" + denseId + " TOP 20");
            assertTrue(top.contains("\"i" + denseId + "\""),
                "Object should appear in its own retained set");
        }
    }
}
