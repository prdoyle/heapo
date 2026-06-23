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

## Result sigils

| Sigil | Meaning |
|---|---|
| `i<n>` | Heap object instance (dense ID) — used in `EXPLAIN i1234`, `RETAINED BY i1234`, and all object output |
| `t<n>` | Table result in history (e.g. prompt shows `heapo t17>`) |
| `s<n>` | Set (bitset) result in history (e.g. prompt shows `heapo s42>`) |
| `h<n>` | History entry reference — used in `CALL h42 name` |

The REPL prompt shows the current result type and ID: `heapo s42>` means THAT is a bitset set saved as history entry 42.

## Workflow

**Prefer `heapo open` over `heapo query` for all multi-step analysis.** `heapo open` gives you a stateful session: `CALL THAT`, named results, `FROM THAT`, `FROM <name>`, and `IN <name>` all require a live session. Use `heapo query` only for truly one-shot lookups.

For agent/LLM use, pipe commands into `heapo open` via stdin with `--output jsonl`:

```bash
printf 'STATUS\nCLASSES\n' | heapo open --output jsonl DUMP
```

Or for a longer investigation:

```bash
{
  echo "TOP 20 BY retainedSize"
  echo "CALL THAT bigLeakers"
  echo "FROM bigLeakers RETAINED BY i1234 TOP 10 BY retainedSize"
} | heapo open --output jsonl DUMP
```

Each command's output appears on stdout as JSONL; read it line by line.

Replace `DUMP` with the actual path to the `.hprof` file in all commands below.

### 1. Orient

```bash
printf 'STATUS\nCLASSES\n' | heapo open --output jsonl DUMP
```

### 2. Find the biggest memory consumers

```bash
printf 'TOP 20 BY retainedSize\n' | heapo open --output jsonl DUMP
```

Identify the classes contributing the most retained memory, then drill in:

```bash
{
  echo "CLASS com.example.Foo TOP 10 BY retainedSize"
  echo "CLASS com.example.Foo SUM retainedSize"
} | heapo open --output jsonl DUMP
```

### 3. Trace what is holding an object in memory

```bash
printf 'EXPLAIN i<id>\n' | heapo open --output jsonl DUMP
```

Each line is the immediate dominator — the object that, if collected, would free everything below it. Walk the chain upward to find the GC root preventing collection. The optional `via` field names the field in the parent object that directly references the child.

### 4. Explore a dominator subtree

```bash
printf 'RETAINED BY i<id> TOP 20 BY retainedSize\n' | heapo open --output jsonl DUMP
```

Shows all objects exclusively retained by `i<id>`, sorted by retained size.

### 5. Save and reuse results across queries

```bash
{
  echo "CLASS com.example.Cache TOP 10 BY retainedSize"
  echo "CALL THAT caches"
  echo "FROM caches RETAINED BY i<id> TOP 10 BY retainedSize"
} | heapo open --output jsonl DUMP
```

`CALL THAT <name>` saves the current result under a name you choose. Use it in subsequent `FROM <name>`, `IN <name>`, or `RETAINED BY <name>` filters within the same session.

### 6. Filter by size threshold

```bash
printf 'CLASS java.lang.String RETAINING > 100000\n' | heapo open --output jsonl DUMP
```

## Full DSL reference

### Pipeline model

Queries compose as: **source** → zero or more **filters** → optional **terminal**.

**Sources** (produce a bitset of objects):

| Source | Description |
|---|---|
| `CLASS <class>` | All instances of a class (use `*` for all objects) |
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
| `WHERE <field> <op> <value>` | Keep objects whose primitive field satisfies the comparison (`>` `>=` `<` `<=` `=`). Class context required (use `CLASS <class>` or add `OF TYPE <class>` before WHERE). Value is a number or `true`/`false`. |

**Terminals** (materialise the bitset into a table result):

| Terminal | Returns |
|---|---|
| `TOP <n> [BY retainedSize]` | Largest-N by retained size |
| `BOTTOM <n> [BY retainedSize]` | Smallest-N by retained size |
| `COUNT` | Total count |
| `MAX retainedSize` | Maximum retained size |
| `SUM retainedSize` | Sum of retained sizes |

With no terminal, the result is stored as a bitset (THAT) and the top 10 are displayed.

**Examples:**

```
CLASS com.example.Foo                          # all Foo instances (bitset)
CLASS com.example.Foo TOP 10 BY retainedSize   # top 10 Foos
CLASS * IN suspects NOT IN excluded TOP 20 BY retainedSize
FROM mySet COUNT
FROM THAT TOP 5 BY retainedSize
```

### Other queries

| Query | Returns |
|---|---|
| `STATUS` | Object and class count |
| `CLASSES [MATCHING <glob>]` | All classes sorted by instance count; glob matches dotted class name |
| `NAMES [MATCHING <glob>]` | All named bitset results; glob filters by name |
| `EXPLAIN <name>` | Show which history command produced the named result (provenance) |
| `TOP <n> [BY retainedSize]` | Largest-N objects across all classes |
| `BOTTOM <n> [BY retainedSize]` | Smallest-N objects across all classes |
| `CLASS <class> RETAINING > <bytes>` | Instances satisfying the comparison (`>` `>=` `<` `<=` `=`) |
| `EXPLAIN i<id>` | Dominator chain from object to GC root |
| `RETAINED BY i<id> [TOP <n>]` | All objects in the dominator subtree |

## Output formats

| Flag | Use when |
|---|---|
| `--output human` | Default; aligned table for human reading |
| `--output jsonl` | One JSON object per line — use this as an LLM |
| `--output json` | Full JSON array |

## JSONL field reference

**TOP / BOTTOM / RETAINING / RETAINED BY rows:**
```json
{"rank":0,"id":"i12345","type":"java.util.HashMap","retainedSize":2097152,"shallowSize":48}
{"rank":1,"id":"i99","type":"java.lang.String","retainedSize":1024,"shallowSize":24,"description":"hello world"}
```

The optional `description` field provides a human-readable summary of the object's value. For `java.lang.String` it contains the string contents (truncated if long); for `java.lang.Class` it names the class represented.

**EXPLAIN rows** (`depth` 0 = the queried object; increasing depth = toward GC root):
```json
{"depth":0,"id":"i12345","type":"java.util.HashMap","retainedSize":2097152,"via":"table"}
{"depth":1,"id":"i99","type":"com.example.Cache","retainedSize":8388608,"via":"cache"}
{"depth":2,"id":"i7","type":"java.lang.Class","retainedSize":16777216,"notes":"com.example.Cache.class"}
```

The optional `via` field names the field in the parent object that directly references this object (`"[N]"` for array slot N, `"(indirect)"` if the dominator has no direct edge). The optional `notes` field carries other freeform context; for `java.lang.Class` objects it names the class represented, indicating the object is held via a static field on that class.

**CLASSES rows:**
```json
{"id":"i88","className":"java.util.HashMap","instanceCount":4201}
```

**COUNT:**
```json
{"className":"java.lang.String","count":182340}
```

**MAX / SUM:**
```json
{"className":"java.lang.String","func":"SUM","retainedSize":41943040}
```

## Analysis guidelines

- **retainedSize** is the memory freed if the object were collected; it includes all objects exclusively dominated by it. This is the primary metric for leak analysis.
- **shallowSize** is the object's own field storage only — not what it transitively points to.
- Convert byte counts to MB (divide by 1 048 576) when presenting to the user.
- Group findings by root cause (e.g. "two Cache instances together hold 450 MB").
- For leak analysis: find objects with large retained sizes, then use EXPLAIN to find what is preventing their collection.
