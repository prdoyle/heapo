# heapo — Design Document

## What It Is

A command-line Java tool for analyzing HPROF heap dumps. Core value proposition: compute
retained heap sizes via dominator tree analysis, then support interactive and scripted queries
over the results. Designed to run on a machine with much less RAM than the machine that
produced the dump.

The tool has two query modes:
- A purpose-built DSL for heap analysis (bitset-oriented, intentionally inexpressive)
- SQL (SELECT/WITH) routed to SQLite for relational queries over tabular results

An LLM can drive both modes effectively — the DSL is intentionally English-like and
performance-visible, and LLMs are well-trained on SQL.

---

## Technology Stack

- **Java 25** (LTS)
- **Panama foreign memory** (`java.lang.foreign` — `MemorySegment`, `Arena`) for all
  memory-mapped index files. Do NOT use the legacy `MappedByteBuffer`.
- **SQLite** via `sqlite-jdbc` for tables and session metadata
- **jOOQ** with hand-crafted table/column definitions (no code generation) for all SQLite
  access. Never use raw JDBC.
- **JLine3** for the interactive REPL
- **picocli** for the outer CLI
- **JUnit 5** for testing
- **Gradle** with Kotlin DSL (`build.gradle.kts`)
- Sealed classes, records, and pattern matching are idiomatic — use them freely.

---

## Memory Model

```
-Xmx = 2 × (numObjects / 8) bytes
```

The JVM heap budget is almost entirely reserved for the visited bitset used during graph
algorithms. Everything else lives in memory-mapped files — the OS page cache is the effective
buffer pool.

Key structures:

| Structure | Location | Notes |
|---|---|---|
| Visited bitset | JVM heap `long[]` | 1 bit per object |
| DFS stack | JVM heap, growable | Used during index build |
| Current `Answer` | JVM heap | Ephemeral, replaced each command |
| All index files | `MemorySegment` (mmap) | OS page cache managed |
| Named results | SQLite or bitset files | Persistent |

**Object IDs** are signed 32-bit integers. This comfortably covers any real heap dump — a
compressed-oops JVM caps the heap at ~32GB, and at minimum object size (16 bytes) that's at
most 2B objects, well within signed `int` range.

The manifest records `objectCount`. If `objectCount > Integer.MAX_VALUE`, or if the manifest
explicitly sets `"idWidth": 64`, use 64-bit IDs throughout. This is not expected in practice
but the design accommodates it for future-proofing and for testing 64-bit code paths with
small synthetic dumps.

**Shallow sizes** are stored right-shifted by 3 (all Java object sizes are multiples of 8
bytes). This fits in a signed `int`. Values that overflow after shifting are clamped to
`Integer.MAX_VALUE` — any object that large is trivially the top retained heap result.

**Retained sizes** can be as large as the full heap — store as `long[]`, no shift.

---

## File Layout

```
myapp.hprof
myapp.hprof.d/
    manifest.json
    indexes/
        class-of.bin                    ← uint32[] objectId → classId (core, from unpack)
        super-class-of.bin              ← uint32[] classId → superclassId (core, from unpack)
        raw-id-lookup-sorted.bin        ← long[] sorted rawIds (core, from unpack)
        raw-id-lookup-dense.bin         ← int[] aligned denseIds (core, from unpack)
        shallow-size.bin                ← int[] right-shifted by 3 (core, from unpack)
        forward-refs-offsets.bin        ← long[] CSR offsets (core, from unpack)
        forward-refs-edges.bin          ← int[] destination denseIds (core, from unpack)
        instance-list-offsets.bin       ← long[] CSR offsets (derived from class-of)
        instance-list-edges.bin         ← int[] denseIds grouped by class
        reverse-refs-offsets.bin        ← long[] CSR offsets (derived from forward-refs)
        reverse-refs-edges.bin          ← int[] source denseIds
        dfs-num.bin                     ← long[] DFS discovery number per denseId
        dfs-vertex.bin                  ← int[] denseId at each DFS position
        dfs-parent.bin                  ← int[] parent denseId in DFS tree
        idom.bin                        ← int[] immediate dominator per denseId
        retained-size.bin               ← long[] retained heap size per denseId
        dominator-children-offsets.bin  ← long[] CSR offsets
        dominator-children-edges.bin    ← int[] children in dominator tree
        retained-size-rank.bin          ← int[] rank by retained size (0 = largest)
        dominator-subtree-size.bin      ← int[] number of objects in dominator subtree
        field-values-<class>-<field>.bin     ← typed arrays per field (lazy, per class)
        fingerprint-<class>-<field>.bin      ← sorted (long hash, int id) pairs (lazy)
        reachability-<name>.bin              ← bitset (lazy, per named result)
        chain-length-<class>-<field>.bin     ← int[] (lazy, per class+field)
    bitsets/
        <uuid>.bin                      ← one file per named BitSet Answer
    sql.db                              ← SQLite: session metadata + all table Answers
```

### Manifest (`manifest.json`)

```json
{
  "hprofFingerprint": "sha256:abc123...",
  "objectCount": 1234567,
  "classCount": 8901,
  "idWidth": 32
}
```

`hprofFingerprint` is SHA-256 of the first 64KB + file size. Detects HPROF replacement
without hashing the whole file. `idWidth` defaults to 32 if absent. Can be set to 64 in
test manifests to force 64-bit ID mode without needing a large dump.

---

## Module Structure

The project is a Gradle multi-module build. Each module hides a specific design decision.

### `model`
No dependencies on other modules. Defines the shared vocabulary:

- `Answer` — sealed interface, the central type everything passes around
  - `BitSetAnswer` — a set of objects (backed by a bitset file or in-memory `long[]`)
  - `TableAnswer` — tabular result (backed by a SQLite table)
  - `ScalarAnswer` — a single number (COUNT, SUM, MAX)
  - `TreeAnswer` — hierarchical output (EXPLAIN)
  - `VoidAnswer` — commands with no output (CALL, FORGET)
- `ObjectId` — `int` newtype, or `long` if idWidth=64
- `ClassId` — `int` newtype
- Basic value types

### `unpack`
Hides the HPROF binary format entirely. No other module knows HPROF exists.

Subcomponents:
- *HPROF record reader* — parses the binary format, emits typed records sequentially
- *Scratch file emitter* — writes raw `(src, dst)` edge pairs and `(rawId, denseId)` pairs
  to temp files as records are encountered. Dense IDs are assigned by incrementing a counter
  (`nextId++`) in encounter order — there is no "dense ID assigner" module, it's just a counter.
- *External merge sort* — fixed RAM buffer external sort. Used here and in `indexes`.
  Consider putting in a shared `util` module.
- *Core file writers* — produce the six core index files and manifest after sorting scratch files
- *Manifest writer*

### `indexes`
Hides on-disk index formats, build algorithms, and Panama memory management.

Subcomponents:
- *Panama infrastructure* — typed `MemorySegment` wrappers, `Arena` lifecycle management,
  CSR reader/builder utilities (a CSR is a pair of offset + edge arrays; the builder takes
  sorted `(src, dst)` pairs and produces both arrays)
- *Index registry* — checks manifest for what's already built, builds missing indexes on
  demand, atomically marks each index complete
- *Per-index builder + reader pairs* — one pair each for:
  - Instance list
  - Reverse refs
  - DFS tree (iterative, not recursive — the heap can be very deep)
  - Dominator tree (Lengauer-Tarjan algorithm — see below)
  - Retained sizes
  - Retained size rank
  - Dominator subtree size
  - Field values (lazy, per class — requires re-scanning the HPROF)
  - Content fingerprints (lazy, per class+field)
  - Reachability (lazy, per named result set)
  - Chain lengths (lazy, per class+field)

### `session`
Hides SQLite and jOOQ. Owns all persistent session state.

Subcomponents:
- *jOOQ schema* — hand-crafted history and names table definitions
- *History manager* — records every command with timestamp and storage pointers,
  walks lineage, supports THAT reconstitution on startup by replaying from the last anchor
- *Names manager* — CALL, FORGET, NAMES; maintains name→history-id bindings.
  `CALL THAT <name>` is an upsert: new binding under `input1`, displaced binding (if any)
  under `input2` of the history row.
- *User table manager* — auto-generates internal SQLite table names, manages their lifecycle

### `query-engine`
Hides the DSL grammar, query planning, and execution strategy. Entry point:
`Answer execute(String command, Session session)`.

Subcomponents:
- *DSL lexer + parser* — hand-written recursive descent, produces an AST
- *Type checker* — propagates static types through the pipeline (e.g. `FOLLOW field`
  resolves to the declared type of that field)
- *Query planner* — selects required indexes, reorders filters by cost tier (O(v/64) bitset
  ops first, O(k) field ops last), estimates build time and warns if >30s
- *Pipeline executor* — drives sources, filters, transformations, and output terminals
- *SQL router* — detects `SELECT`/`WITH` prefix, delegates to SQLite via session
- *Session command handlers* — EXPLAIN, CLASSES, FIELDS, DESCRIBE, STATUS, HELP, UNDO

### `cli`
Hides JLine3, picocli, and output format details.

Subcomponents:
- *picocli setup* — outer CLI: `--explore`, `--quick "<query>"`, HPROF file argument
- *JLine3 REPL* — readline editing, persistent command history via JLine
- *Tab completer* — grammar-aware, feeds off DSL parser state to suggest valid next tokens
- *Output formatters* — YAML (default), JSON, JSONL, human-readable; EXPLAIN tree renderer

---

## The Session Model

### History and Names

The SQLite database (`sql.db`) contains:

**`history` table:**

| column | type | notes |
|--------|------|-------|
| id | integer PK | auto-increment |
| command | text | full command string as typed |
| timestamp | integer | epoch ms |
| bitset_file | text | UUID filename in `bitsets/`, null if not a BitSet |
| sql_table | text | internal SQLite table name, null if not a Table |
| input1 | integer FK→history.id | primary input |
| input2 | integer FK→history.id | displaced name binding, or second input |

**`names` table:**

| column | type | notes |
|--------|------|-------|
| name | text PK | user-chosen name |
| history_id | integer FK→history.id | the history row whose storage pointer is used |

Every command is recorded in history. Commands that produce a `TableAnswer` write to SQLite
immediately and record the table name. Commands that produce a `BitSetAnswer` only write to
disk if the user issues `CALL THAT <name>` — otherwise the bitset lives in memory as THAT.

`CALL THAT <name>` is a naming operation: it upserts `names`, recording the previous binding
(if any) as `input2` in the history row, enabling UNDO.

On startup, THAT is lazily reconstituted: find the last history entry with a non-null storage
pointer, replay subsequent commands forward in order. All results are immutable so re-execution
always produces the same answer.

### Answer Lifecycle

- **BitSetAnswer**: lives in JVM heap as `long[]` until named via `CALL THAT`. Then written
  to `bitsets/<uuid>.bin`. Never written otherwise.
- **TableAnswer**: always written to SQLite immediately, even if not named. Tables are small
  by construction (bounded by TOP n or query limit).
- **ScalarAnswer, TreeAnswer, VoidAnswer**: ephemeral, displayed and discarded.

All named results are immutable. DROP (we call it `FORGET`) removes the name from the names
table but leaves the underlying data intact. UNDO restores the previous name binding.

### User-Visible Commands

```
CALL THAT <name>          bind name to current Answer (upsert)
CALL #<id> <name>         bind name to any history entry by ID
FORGET <name>             remove name (data retained)
NAMES [MATCHING <pat>]    list active names
HISTORY [n]               show last n history entries
UNDO                      reverse last naming/forgetting
```

---

## The DSL

### Principles

- **Intentionally inexpressive** — cannot accidentally write O(n²) queries
- **Performance-visible** — each keyword maps to a known index and cost
- **Statically typed** — types propagate through the pipeline from syntax
- **Pipeline-ordered** — source → filters → transformations → FIELD → output
- **Default output** — implicit TOP 10 by retained size if no output primitive given

### Sources

```
ALL <class>           instances of class and subclasses
ALL Object            all objects
FROM <name>           named BitSet or Table Answer
FROM THAT             current Answer
FROM {#id, #id}       set literal
```

### Set Filters (planner reorders: O(v/64) first, O(k) last)

```
OF TYPE [EXACTLY] <class>          O(v/64)
IN <name>                          O(v/64) — bitset AND
NOT IN <name>                      O(v/64) — bitset AND-NOT
REACHABLE FROM <name>              O(v/64) after I12 build
DOMINATED BY <name>                O(v/64) after subtree walk
RETAINING <size>                   O(log v + v/64)
SIZED > n / < n / BETWEEN n AND m  O(k) — collections, arrays, strings
REFERENCING <name> VIA <field>     O(k)
REFERENCED BY <name> VIA <field>   O(k)
COLLECTIONS / MAPS / ARRAYS        O(v/64)
ONLY WEAKLY REACHABLE              O(v/64)
CHAIN VIA <field> LONGER THAN n    O(1) after I13 build
DUPLICATES BY <field>              O(k) using I11
FIELD <expr>                       O(k) — per-object expression, always last
```

### Set Transformations (always after filters)

```
FOLLOW <field>
UNFOLLOW <field> TO <class>
COLLECTIONS ELEMENTS [OF TYPE <class>]
ARRAYS ELEMENTS [OF TYPE <class>]
MAPS KEYS/VALUES/ENTRIES [OF TYPE <class>]
DOMINATOR SUBTREE
```

### Output Terminals (BitSet → Answer)

```
TOP n BY <field|retainedSize>
BOTTOM n BY <field|retainedSize>
GROUP BY <field> [TOP n BY COUNT|retainedSize]
AGGREGATE COUNT
AGGREGATE SUM|MAX retainedSize
PRINT { graphql-style projection }
```

### SQL

Any input beginning with `SELECT` or `WITH` is routed to SQLite. SQL can only reference names
backed by TableAnswers — it cannot directly reference BitSet names. The result is always
written to SQLite immediately. `CALL THAT <name>` after SQL just binds the name.

### Session and Inspection Commands

```
EXPLAIN <name>             derivation chain and static type
EXPLAIN TOP n FROM <name>  dominant retention paths
EXPLAIN #<id>              retention path for one object
DESCRIBE <pattern>         classes, fields, modules, classloaders
FIELDS <class>             fields with types and stats
CLASSES [MATCHING <pat>]   instance histogram
STATUS                     index inventory and build status
HELP <tokens>              grammar-aware help
UNDO                       reverse last naming operation
```

---

## The Dominator Tree (Lengauer-Tarjan)

This is the hardest algorithm in the system. It runs in four sequential phases, each reading
mmap'd inputs and writing mmap'd outputs:

1. **DFS traversal** — iterative DFS from the synthetic super-root (a node with edges to all
   GC roots, assigned `denseId = objectCount`). Produces `dfs-num`, `dfs-vertex`,
   `dfs-parent`.
2. **Semidominator computation** — reads reverse-refs and DFS files, produces semi, ancestor,
   label, idom (partial), bucket — all mmap'd temp files.
3. **Finalize dominators** — single sequential forward pass, completes `idom[]`.
4. **Cleanup** — delete temp files.

Path compression (eval) must be iterative — no recursion. The call stack cannot hold a path
through the entire heap graph.

The super-root has no corresponding real object; it's a synthetic node used as the unique
entry point for the dominator algorithm. When reporting results, filter it out.

---

## Index Build Dependency Order

```
HPROF scan
  └─ class-of, super-class-of, shallow-size, forward-refs CSR, raw-id lookup, manifest
       ├─ instance-list (from class-of — inverse mapping)
       ├─ reverse-refs (from forward-refs)
       └─ DFS tree (from forward-refs)
            └─ dominator tree / idom (from reverse-refs + DFS)
                 └─ retained-size (from shallow-size + idom)
                      ├─ retained-size-rank (external sort of retained-size)
                      └─ dominator-subtree-size (from idom + retained-size)

On demand (lazy):
  field-values-<class>-<field>     ← requires HPROF re-scan for that class
  fingerprint-<class>-<field>      ← from field-values (external sort)
  chain-length-<class>-<field>     ← from forward-refs + field-values
  reachability-<name>              ← from forward-refs + named result bitset
```

All sort operations use **external merge sort with fixed RAM buffers** — never in-memory sort.
This is critical for scalability. Implement a general-purpose external merge sort utility early.

---

## Output Format

Default output is YAML. Three states for unprojected references:

```yaml
- id: "#4521"
  type: com.example.Session
  retainedSize: 847KB
  fields:
    id: "sess-a3f9b2"
    user:
      id: "#8821"
      type: com.example.User
      fields: not projected
    activeRequests: null
```

Output format flags: `--output human` (default), `--output yaml`, `--output json`,
`--output jsonl`. `--quick` defaults to jsonl.

---

## Known Limitations (v1)

- Off-heap memory (Netty, Lucene MMapDirectory, Unsafe) not visible in HPROF
- No multi-dump comparison
- SQL lineage tracking is best-effort (SQL is a black box in the dependency graph)
- Field values for a class require a second sequential HPROF scan (deferred to Phase 8)
