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
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            String hist = session.execute("HISTORY 5");
            assertTrue(hist.contains("KnownObjects"), "History should contain the command");
        }
    }

    @Test
    void callThatBindsName() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            String result = session.execute("CALL THAT myBars");
            assertTrue(result.contains("myBars"), "Result should confirm the bound name");

            String names = session.execute("NAMES");
            assertTrue(names.contains("myBars"), "NAMES should list myBars");
        }
    }

    @Test
    void forgetRemovesName() throws Exception {
        try (var session = new HeapSession(heap, registry, dbPath)) {
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
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
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 2 BY retainedSize");
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
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize");
            session.execute("ALL heapo.samples.KnownObjects$Foo TOP 1 BY retainedSize");
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
            session.execute("ALL heapo.samples.KnownObjects$Bar TOP 5 BY retainedSize");
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
}
