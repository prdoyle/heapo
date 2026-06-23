# heapo — Implementation Plan

## Before You Start

Read `heapo-design.md` in full. This plan assumes you know the design. Key things to keep
in mind throughout:

- **Never be in a broken state.** Each phase should produce something runnable. Don't start
  the next phase until the current one is complete and tested.
- **Test against real artifacts.** Don't mock the HPROF parser, the index files, or SQLite.
  Test them against the real thing. Mocks of file formats lead to bugs that only appear in
  production.
- **Keep the memory model honest.** The whole point of this tool is that it runs on a machine
  with much less RAM than the heap being analyzed. Don't load entire index files into memory.
  Use `MemorySegment` (mmap) throughout `indexes`. Trust the OS page cache.
- **External merge sort is not optional.** Any sort over index data must use fixed RAM
  buffers. Implement this early (Phase 2) and use it everywhere.
- **Iterative, not recursive.** Any algorithm that traverses the object graph (DFS,
  path compression) must be iterative. The heap graph can be arbitrarily deep.

---

## Project Structure

Gradle multi-module project with Kotlin DSL. Modules:

```
heapo/
    model/           ← Answer types and shared vocabulary, no dependencies
    unpack/          ← HPROF parser + core index file writer
    indexes/         ← derived index builders and readers
    session/         ← SQLite session: history, names, tables
    query-engine/    ← DSL parser, planner, executor; SQL router
    cli/             ← REPL, output formatting, picocli
    hprof-samples/   ← generates test HPROF files (not a library, see below)
```

### hprof-samples

This is a standalone Java program (not a test library) that allocates known objects and dumps
them using `HotSpotDiagnosticMXBean`. It is compiled and run by Gradle as a build step,
producing HPROF files in `build/hprof-samples/`. Tests read these files via a system property.

```kotlin
// in root build.gradle.kts
val generateHprofFiles = tasks.register<JavaExec>("generateHprofFiles") {
    classpath = project(":hprof-samples").sourceSets["main"].runtimeClasspath
    mainClass = "heapo.samples.GenerateHprofFiles"
    outputs.dir(layout.buildDirectory.dir("hprof-samples"))
    systemProperty("output.dir", outputs.files.singleFile)
}

project(":unpack") {
    tasks.test {
        dependsOn(generateHprofFiles)
        systemProperty("hprof.samples.dir",
            rootProject.layout.buildDirectory.dir("hprof-samples").get().asFile)
    }
}
// repeat for other modules that need HPROF files
```

The sample generator allocates objects with known structure, holds them in static fields (so
GC doesn't collect them), then dumps. Example:

```java
public class KnownObjects {
    // Hold references statically to prevent GC collection before dump
    static Object foo1, foo2, bar1;

    static void allocate() {
        foo1 = new Foo();
        foo2 = new Foo();
        bar1 = new Bar();
        ((Foo) foo1).next = (Foo) foo2;
        ((Bar) bar1).owner = (Foo) foo1;
    }

    static class Foo { Foo next; }
    static class Bar { Foo owner; }
}
```

Generate multiple HPROF files for different scenarios:
- `known-objects.hprof` — small, known structure, used for most unit tests
- `deep-chain.hprof` — long linked list, for testing iterative DFS
- `large-collections.hprof` — oversized collections, for collection-related tests

**Tests use `@BeforeAll` to unpack once per test class**, not once per test method:

```java
class UnpackerTest {
    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir"));
    static UnpackedHeap heap;

    @BeforeAll
    static void setup() throws Exception {
        var hprofPath = samplesDir.resolve("known-objects.hprof");
        var outDir = Files.createTempDirectory("heapo-test");
        heap = Unpacker.unpack(hprofPath, outDir);
    }
}
```

A real heap dump contains JVM internals and test framework objects alongside your known
objects. Write assertions that find your objects by class name and verify their relationships,
rather than asserting exact total counts.

---

## Phase 1: HPROF Parsing

**Goal:** prove we can read a real heap dump and extract meaningful data. No index files yet.

### Tasks

1. Set up the Gradle multi-module project skeleton. All modules present but mostly empty.
   Get `./gradlew build` passing on an empty project.

2. Implement `hprof-samples/GenerateHprofFiles` with `KnownObjects` as described above.
   Wire the Gradle task. Verify the HPROF files are generated at build time.

3. Implement an HPROF record reader in `unpack` that parses:
   - Header (magic, id size, timestamp)
   - `LOAD_CLASS` records (class serial → name)
   - `HEAP_DUMP` / `HEAP_DUMP_SEGMENT` containing:
     - `GC_CLASS_DUMP` — class metadata, field descriptors
     - `GC_INSTANCE_DUMP` — object instances with field data
     - `GC_OBJ_ARRAY_DUMP` — object arrays
     - `GC_PRIM_ARRAY_DUMP` — primitive arrays
     - GC root records (`GC_ROOT_JNI_GLOBAL`, `GC_ROOT_JNI_LOCAL`, `GC_ROOT_JAVA_FRAME`,
       `GC_ROOT_STICKY_CLASS`, `GC_ROOT_THREAD_OBJ`, etc.)
   - `STRING` records (for class names)

4. Write a main method that reads a dump and prints a class histogram:
   class name → instance count, sorted by count descending. This is your first milestone.

### Tests

- Histogram of `known-objects.hprof` contains at least 1 instance of `KnownObjects$Foo`
  and 1 instance of `KnownObjects$Bar`.

---

## Phase 2: Unpack

**Goal:** produce the six core index files from a real dump.

### Tasks

1. Implement **external merge sort** as a general utility (consider a `util` module or
   package). It must use a fixed RAM buffer (configurable, default 64MB). It must handle
   sorting by arbitrary comparators. It will be used throughout the project.

2. During the HPROF scan, assign dense IDs by incrementing `nextId++` in encounter order.
   Emit two scratch files:
   - Raw `(rawId: long, denseId: int)` pairs
   - Raw `(srcDenseId: int, dstRawId: long)` edge pairs

3. After the scan, external-sort both scratch files and produce:
   - `raw-id-lookup-sorted.bin` and `raw-id-lookup-dense.bin` (sorted by rawId for O(log n)
     lookup in both directions)
   - `forward-refs-offsets.bin` and `forward-refs-edges.bin` (CSR from sorted edge pairs,
     after resolving dstRawId → dstDenseId via the lookup table)
   - `class-of.bin` — can be written directly during scan
   - `super-class-of.bin` — written during scan from `GC_CLASS_DUMP` records
   - `shallow-size.bin` — written during scan, right-shifted by 3, clamped to `Integer.MAX_VALUE`

4. Write `manifest.json` with `objectCount`, `classCount`, `hprofFingerprint`, `idWidth`.

5. The entry point: `Unpacker.unpack(Path hprofFile, Path outputDir)` — creates the
   `outputDir/indexes/` and `outputDir/bitsets/` subdirectories, writes all files.

### Tests

- Unpack `known-objects.hprof`
- Assert `objectCount` in manifest is reasonable (contains JVM objects + your known objects)
- Find the dense IDs of `KnownObjects$Foo` instances by scanning `class-of.bin`
- Assert that the forward-refs CSR contains edges from `foo1` to `foo2` (via `Foo.next`)
  and from `bar1` to `foo1` (via `Bar.owner`)
- Assert shallow sizes are non-zero and right-shifted correctly

---

## Phase 3: Derived Indexes

**Goal:** build instance list through dominator subtree size from the core files.

### Tasks

1. Implement **Panama infrastructure** in `indexes`:
   - `IndexFile` — a wrapper around `MemorySegment` with typed read/write methods
     (`readInt(long offset)`, `readLong(long offset)`, `writeInt(long offset, int value)`,
     etc.)
   - `Arena`-based lifecycle — each index reader opens a `MemorySegment` via
     `Arena.ofShared()` or `Arena.ofAuto()`; the arena is closed when the reader is closed
   - `CsrReader` — utility for reading CSR files; given a vertex ID, returns the range of
     edges for that vertex
   - `CsrBuilder` — utility that takes sorted `(src, dst)` pairs (as streams or iterators)
     and writes CSR offset + edge files

2. Implement **index registry** — a class that:
   - Reads the manifest to determine `objectCount` and `idWidth`
   - Knows the expected path and existence state of each index file
   - Provides `ensureBuilt(IndexType)` which builds the index if absent, marks it complete
     atomically (write to temp file, then atomic rename)
   - Provides typed reader accessors

3. Build derived indexes in dependency order. Each gets a builder and a reader:

   **Instance list** (`instance-list-offsets.bin`, `instance-list-edges.bin`):
   - Scan `class-of.bin`, group dense IDs by class ID
   - Use external sort or counting sort (class IDs are bounded) to produce CSR

   **Reverse refs** (`reverse-refs-offsets.bin`, `reverse-refs-edges.bin`):
   - Transpose `forward-refs` CSR using external sort of `(dst, src)` pairs

   **DFS tree** (`dfs-num.bin`, `dfs-vertex.bin`, `dfs-parent.bin`):
   - Iterative DFS from synthetic super-root (denseId = objectCount)
   - Super-root has synthetic edges to all GC roots (record GC roots during unpack)
   - Uses the visited bitset in JVM heap
   - Objects not reachable from GC roots get DFS number = -1

   **Dominator tree** (`idom.bin`):
   - Lengauer-Tarjan algorithm, four phases as described in the design doc
   - All intermediate structures (semi, ancestor, label, bucket) are mmap'd temp files
   - Iterative path compression

   **Retained sizes** (`retained-size.bin`, `dominator-children-offsets.bin`,
   `dominator-children-edges.bin`):
   - Walk dominator tree bottom-up using DFS order
   - retained[v] = shallow[v] + sum(retained[c] for c in children[v])

   **Retained size rank** (`retained-size-rank.bin`):
   - External sort of `(retainedSize, denseId)` pairs descending
   - rank[denseId] = position in sorted order

   **Dominator subtree size** (`dominator-subtree-size.bin`):
   - Count objects in each dominator subtree
   - Single pass in reverse DFS order

### Tests

Using `known-objects.hprof` with known structure:

- Instance list for `KnownObjects$Foo` contains exactly the expected dense IDs
- `foo1` dominates `foo2` (since `foo2` is only reachable via `foo1.next`)
- `bar1` does not dominate `foo1` (foo1 is reachable from GC roots independently)
- `retained-size(foo1)` ≥ `shallow-size(foo1) + shallow-size(foo2)`
- Retained size rank: the object with the largest retained size has rank 0

---

## Phase 4: First End-to-End Query

**Goal:** `ALL <class> TOP n BY retainedSize` works from a command line. No REPL yet.

### Tasks

1. In `model`, define:
   ```java
   public sealed interface Answer permits BitSetAnswer, TableAnswer, ScalarAnswer,
                                          TreeAnswer, VoidAnswer {}
   public record BitSetAnswer(long[] bits, int objectCount) implements Answer {}
   public record TableAnswer(String sqlTableName, int rowCount) implements Answer {}
   public record ScalarAnswer(long value, String label) implements Answer {}
   // TreeAnswer and VoidAnswer TBD
   ```

2. In `query-engine`, implement a minimal DSL parser that handles only:
   ```
   ALL <className> TOP <n> BY retainedSize
   ALL Object TOP <n> BY retainedSize
   ```

3. Implement the pipeline executor for this shape:
   - Source: read instance list for the class (or all objects)
   - Sort: use retained-size-rank to find the top n
   - Output: produce a list of `(denseId, className, retainedSize, shallowSize)`

4. Implement basic YAML output for this result shape.

5. Wire up a `main` method in `cli` (or a test) that takes an HPROF path and runs the query.

### Tests

- `ALL heapo.samples.KnownObjects$Bar TOP 1 BY retainedSize` returns `bar1`
  with retained size ≥ retained size of `foo1` + retained size of `foo2`
  (because `bar1` → `foo1` → `foo2`)

---

## Phase 5: Session and REPL

**Goal:** interactive tool with named results that persist across restarts.

### Tasks

1. In `session`, define the jOOQ schema for `history` and `names` tables as described in
   the design doc. Use jOOQ's `DSL.createTableIfNotExists` on startup.

2. Implement `HistoryManager`, `NamesManager`, `UserTableManager`.

3. Implement THAT lifecycle: in-memory `Answer` reference, lazy reconstitution on startup
   by replaying from the last history entry with a non-null storage pointer.

4. Implement session commands: `CALL THAT <name>`, `CALL #<id> <name>`, `FORGET <name>`,
   `NAMES`, `HISTORY [n]`.

5. Wire JLine3 REPL in `cli`. Basic readline editing and command history via JLine's
   built-in history (separate from the session history table).

6. Wire picocli: `--explore` (build I1–I9 upfront, then enter REPL),
   `--quick "<query>"` (build only required indexes, execute, exit, jsonl output).

### Tests

- Execute a sequence of commands, verify history table contents
- `CALL THAT foo` then restart, verify name is restored
- `FORGET foo` removes from names table, data still in SQLite/bitsets dir
- THAT is correctly reconstituted after restart

---

## Phase 6: Expand DSL

**Goal:** cover the most important use cases. Add one operation at a time, each with tests.

Priority order:

1. `FROM <name>` and `FROM THAT` sources
2. `IN <name>` and `NOT IN <name>` filters (bitset AND / AND-NOT)
3. `DOMINATED BY <name>` filter
4. `REACHABLE FROM <name>` filter (requires I12 — implement I12 builder here)
5. `FOLLOW <field>` transformation
6. `AGGREGATE COUNT`
7. `EXPLAIN #<id>` — walk idom[] upward to GC roots, format as tree
8. `EXPLAIN TOP n FROM <name>`
9. `CLASSES [MATCHING <pattern>]`
10. `FIELDS <class>`
11. `STATUS`
12. `GROUP BY <field> [TOP n BY COUNT|retainedSize]`

For each operation:
- Extend the DSL parser
- Extend the type checker
- Implement the executor
- Add tests

---

## Phase 7: SQL Integration

**Goal:** `SELECT`/`WITH` queries work and compose with DSL results.

### Tasks

1. In `query-engine`, detect `SELECT`/`WITH` prefix and route to SQLite via `session`.

2. In `session`, `UserTableManager` executes the SQL via jOOQ, writes the result to a new
   SQLite table with an auto-generated name, returns a `TableAnswer`.

3. Every `TableAnswer` produced by either DSL output terminals or SQL is written to SQLite
   immediately. All such tables include a `dense_id` column.

4. `CALL THAT <name>` after a SQL query just adds a row to `names` — no re-execution.

### Tests

- DSL query → `CALL THAT foo` → `SELECT * FROM foo WHERE ...` → `CALL THAT bar`
- Verify `bar` is queryable and correct
- Verify history records both the DSL command and the SQL command

---

## Phase 8: On-Demand Indexes and Remaining DSL

**Goal:** complete coverage of the use cases.

### Tasks

1. **Field values (I10)** — implement HPROF re-scan for a specific class. This is the
   second use of the HPROF reader. Wire into index registry as a lazy per-class build.

2. **Content fingerprints (I11)** — from I10 via external sort of `(hash, denseId)` pairs.

3. **Chain lengths (I13)** — from forward-refs and I10.

4. Remaining DSL operations (add in any order, each with tests):
   - `RETAINING <size>` filter
   - `SIZED > n / < n / BETWEEN n AND m` filter
   - `REFERENCING <name> VIA <field>` and `REFERENCED BY <name> VIA <field>`
   - `COLLECTIONS / MAPS / ARRAYS` type filters
   - `ONLY WEAKLY REACHABLE`
   - `CHAIN VIA <field> LONGER THAN n`
   - `DUPLICATES BY <field>`
   - `FIELD <expr>` — per-object expression filter
   - `UNFOLLOW <field> TO <class>`
   - `COLLECTIONS ELEMENTS`, `MAPS KEYS/VALUES/ENTRIES`, `ARRAYS ELEMENTS`
   - `DOMINATOR SUBTREE` transformation
   - `BOTTOM n BY <field>`
   - `AGGREGATE SUM/MAX retainedSize`
   - `PRINT { graphql-style projection }`

---

## Phase 9: Polish

1. **`UNDO`** — reverse last CALL/FORGET by restoring previous name binding from `input2`
   in the history row.

2. **Tab completion** — wire DSL parser state into JLine3 completer. At any point in a
   partial command, suggest valid next tokens based on the grammar and current pipeline type.

3. **Cost warnings** — query planner estimates build time for missing indexes. Warn and
   confirm for builds estimated >30 seconds (skip in `--quick` mode).

4. **`--output` flags** — `--output human|yaml|json|jsonl`

5. **Performance profiling** — run on a real large heap dump (≥1GB). Profile and fix
   bottlenecks, particularly in the dominator algorithm and external sort.

6. **`DESCRIBE <pattern>`** — rich glob-based inspection of classes, fields, modules,
   classloaders.

7. **`HELP <tokens>`** — grammar-aware help at any point in a partial command.

---

## Implementation Notes

### HPROF Format

The HPROF format is documented in the OpenJDK source at:
`src/hotspot/share/services/heapDumper.cpp`

Key points:
- The header contains `idSize` (4 or 8 bytes per raw object ID in the file).
  Handle both. Your dense IDs are always 32-bit regardless.
- `HEAP_DUMP_SEGMENT` records can be very large. Do not load them into memory;
  stream through them.
- Class names in `LOAD_CLASS` records reference `STRING` records that appear earlier
  in the file. Build a string table during the scan.
- `GC_CLASS_DUMP` contains the instance field descriptors for a class. You need these
  to parse `GC_INSTANCE_DUMP` records correctly.

### Lengauer-Tarjan

The classic paper is:
"A Fast Algorithm for Finding Dominators in a Flowgraph" — Lengauer & Tarjan, 1979.

A good implementation reference is the Boost graph library's dominator tree implementation.
Key correctness requirement: the `eval` function (path compression) must be iterative.
The `link` function can use simple tree linking for correctness; the sophisticated version
is an optimization.

### jOOQ

Use `DSLContext` from `DSL.using(connection, SQLDialect.SQLITE)`. Hand-craft your table
and field definitions as static constants:

```java
public static final Table<Record> HISTORY = DSL.table("history");
public static final Field<Long> HISTORY_ID = DSL.field("id", Long.class);
public static final Field<String> COMMAND = DSL.field("command", String.class);
// etc.
```

For user result tables (dynamic schema), use jOOQ's plain SQL API with
`DSL.field(DSL.name(...))` for dynamic column names.

### Panama Memory API

```java
// Opening a file for reading
try (var arena = Arena.ofShared()) {
    var channel = FileChannel.open(path, StandardOpenOption.READ);
    var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
    int value = segment.get(ValueLayout.JAVA_INT, offset * 4L);
}
```

Use `ValueLayout.JAVA_INT` for `int[]` files, `ValueLayout.JAVA_LONG` for `long[]` files.
Always use `long` for byte offsets to avoid overflow on files >2GB.

For writing during index builds, use `READ_WRITE` map mode. Write to a temp file, then
atomically rename to the final path when complete — this prevents corrupt partial writes
from being read by subsequent runs.

### External Merge Sort

The sort buffer size determines peak memory usage during index builds. 64MB is a reasonable
default. The merge phase reads from k sorted runs using a k-way merge (priority queue of
iterators). Output is written sequentially. Key interface:

```java
ExternalMergeSort.sort(
    Path inputFile,
    Path outputFile,
    Path tempDir,
    int recordSizeBytes,
    Comparator<ByteBuffer> comparator,
    int maxRamBytes
);
```

Keep record sizes fixed within a sort operation — variable-length records complicate
the merge phase unnecessarily.
