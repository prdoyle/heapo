---
description: Analyse a Java heap dump using heapo. Use when the user wants to explore memory usage, find memory leaks, identify large retained objects, or investigate a JVM heap dump (.hprof file).
when_to_use: Triggered by phrases like: heap dump, HPROF file, memory leak, OutOfMemoryError, retained size, dominator tree, OOM, what's eating memory, heap analysis, large objects, heap exploration.
argument-hint: [path/to/dump.hprof]
---

Analyse the heap dump at `$ARGUMENTS`. If no path was provided, ask the user for it before proceeding.

## Commands

| Command | Description |
|---|---|
| `heapo open <dump.hprof>` | Build indexes and start interactive REPL |
| `heapo query <dump.hprof> <query...>` | Run one query and exit |
| `heapo unpack <dump.hprof>` | Pre-build indexes without querying |
| `heapo skill` | Print this skill file |

All commands accept `-d <dir>` / `--heap-dir <dir>` to specify where indexes are stored (default: `<dump>.d/` next to the HPROF file). Indexes are cached; subsequent runs reuse them and are fast.

## Workflow

Run queries non-interactively with `heapo query`. The default output is human-readable; use `--output jsonl` when parsing results programmatically.

Replace `DUMP` with the actual path to the `.hprof` file in all commands below.

### 1. Orient

```bash
heapo query DUMP STATUS
heapo query DUMP CLASSES
```

### 2. Find the biggest memory consumers

```bash
heapo query DUMP --output jsonl TOP 20 BY retainedSize
```

Identify the classes contributing the most retained memory, then drill in:

```bash
heapo query DUMP --output jsonl ALL com.example.Foo TOP 10 BY retainedSize
heapo query DUMP --output jsonl ALL com.example.Foo AGGREGATE SUM retainedSize
```

### 3. Trace what is holding an object in memory

```bash
heapo query DUMP --output jsonl EXPLAIN #<id>
```

Each line is the immediate dominator — the object that, if collected, would free everything below it. Walk the chain upward to find the GC root preventing collection.

### 4. Explore a dominator subtree

```bash
heapo query DUMP --output jsonl RETAINED BY #<id> TOP 20 BY retainedSize
```

Shows all objects exclusively retained by `#<id>`, sorted by retained size.

### 5. Filter by size threshold

```bash
heapo query DUMP --output jsonl ALL java.lang.String RETAINING > 100000
```

## Full DSL reference

### Pipeline model

Queries compose as: **source** → zero or more **filters** → optional **terminal**.

**Sources** (produce a bitset of objects):

| Source | Description |
|---|---|
| `ALL <class>` | All instances of a class (use `*` for all objects) |
| `FROM <name>` | Named bitset result or built-in name |
| `FROM THAT` | Current result |

**Built-in names** (use directly in `FROM`, `IN`, `RETAINED BY` without `CALL THAT`):

| Name | Contents |
|---|---|
| `GcRoots` | All GC root objects |
| `Threads` | All `java.lang.Thread` instances |
| `ClassLoaders` | All `java.lang.ClassLoader` instances |
| `SoftReferences` | All `java.lang.ref.SoftReference` instances |
| `WeakReferences` | All `java.lang.ref.WeakReference` instances |
| `PhantomReferences` | All `java.lang.ref.PhantomReference` instances |

**Filters** (narrow the bitset, O(v/64) bitset operations):

| Filter | Description |
|---|---|
| `IN <name>` | Bitset AND — keep objects present in the named set |
| `NOT IN <name>` | Bitset AND-NOT — exclude objects present in the named set |
| `RETAINED BY <name>` | Keep objects dominated (exclusively retained) by any object in the named set |
| `RETAINING > <bytes>` | Keep objects whose retained size satisfies the comparison (`>` `>=` `<` `<=` `=`) |
| `OF TYPE <class>` | Keep objects whose runtime type is `class` or any subclass |
| `OF TYPE EXACTLY <class>` | Keep objects whose runtime type is exactly `class` (no subclasses) |
| `SIZED > <bytes>` | Keep objects whose shallow (own field) size satisfies the comparison |
| `REFERENCING <name>` | Keep objects that have a direct outgoing reference to any object in the named set |
| `REFERENCED BY <name>` | Keep objects directly referenced (pointed to) by any object in the named set |
| `REACHABLE FROM <name>` | Keep objects transitively reachable (by following forward refs) from any object in the named set |

**Terminals** (materialise results):

| Terminal | Returns |
|---|---|
| `TOP <n> BY retainedSize` | Largest-N by retained size |
| `BOTTOM <n> BY retainedSize` | Smallest-N by retained size |
| `AGGREGATE COUNT` | Total count |
| `AGGREGATE MAX retainedSize` | Maximum retained size |
| `AGGREGATE SUM retainedSize` | Sum of retained sizes |

With no terminal, the result is stored as a bitset (THAT) and the top 10 are displayed.

**Examples:**

```
ALL com.example.Foo                          # all Foo instances (bitset)
ALL com.example.Foo TOP 10 BY retainedSize   # top 10 Foos
ALL * IN suspects NOT IN excluded TOP 20 BY retainedSize
FROM mySet AGGREGATE COUNT
FROM THAT TOP 5 BY retainedSize
```

### Other queries

| Query | Returns |
|---|---|
| `STATUS` | Object and class count |
| `CLASSES [MATCHING <glob>]` | All classes sorted by instance count; glob matches dotted class name |
| `NAMES [MATCHING <glob>]` | All named bitset results; glob filters by name |
| `EXPLAIN <name>` | Show which history command produced the named result (provenance) |
| `TOP <n> BY retainedSize` | Largest-N objects across all classes |
| `BOTTOM <n> BY retainedSize` | Smallest-N objects across all classes |
| `ALL <class> RETAINING > <bytes>` | Instances satisfying the comparison (`>` `>=` `<` `<=` `=`) |
| `EXPLAIN #<id>` | Dominator chain from object to GC root |
| `RETAINED BY #<id> [TOP <n> BY retainedSize]` | All objects in the dominator subtree |

## Output formats

| Flag | Use when |
|---|---|
| `--output human` | Default; aligned table for human reading |
| `--output jsonl` | One JSON object per line — use this as an LLM |
| `--output json` | Full JSON array |

## JSONL field reference

**TOP / BOTTOM / RETAINING / RETAINED BY rows:**
```json
{"rank":0,"id":"#12345","type":"java.util.HashMap","retainedSize":2097152,"shallowSize":48}
```

**EXPLAIN rows** (`depth` 0 = the queried object; increasing depth = toward GC root):
```json
{"depth":0,"id":"#12345","type":"java.util.HashMap","retainedSize":2097152}
{"depth":1,"id":"#99","type":"com.example.Cache","retainedSize":8388608}
```

**CLASSES rows:**
```json
{"id":"#88","className":"java.util.HashMap","instanceCount":4201}
```

**AGGREGATE COUNT:**
```json
{"className":"java.lang.String","count":182340}
```

**AGGREGATE MAX / SUM:**
```json
{"className":"java.lang.String","func":"SUM","retainedSize":41943040}
```

## Analysis guidelines

- **retainedSize** is the memory freed if the object were collected; it includes all objects exclusively dominated by it. This is the primary metric for leak analysis.
- **shallowSize** is the object's own field storage only — not what it transitively points to.
- Convert byte counts to MB (divide by 1 048 576) when presenting to the user.
- Group findings by root cause (e.g. "two Cache instances together hold 450 MB").
- For leak analysis: find objects with large retained sizes, then use EXPLAIN to find what is preventing their collection.
