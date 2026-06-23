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
| `i<n>` | Heap object instance (dense ID) — used in `EXPLAIN i1234` and all object output |
| `s<n>` | Set (bitset) result in history (e.g. prompt shows `heapo s42>`) |
| `h<n>` | History entry reference — used in `CALL h42 name` |

The REPL prompt shows the current result type and ID: `heapo s42>` means THAT is a bitset saved as history entry 42.

## Workflow

**Prefer `heapo open` over `heapo query` for all multi-step analysis.** `heapo open` gives you a stateful session: `CALL THAT`, named results, `THAT` as a source, and `IN <name>` all require a live session. Use `heapo query` only for truly one-shot lookups.

For agent/LLM use, pipe commands into `heapo open` via stdin with `--output jsonl`:

```bash
printf 'STATUS\nCLASSES\n' | heapo open --output jsonl DUMP
```

Or for a longer investigation:

```bash
{
  echo "TOP 20 BY retainedSize"
  echo "CALL THAT bigLeakers"
  echo "bigLeakers RETAINED BY i1234 TOP 10 BY retainedSize"
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

Each line is the immediate dominator — the object that, if collected, would free everything below it. Walk the chain upward to find the GC root preventing collection. The optional `field` names the field in this object that directly references the object on the prior line.

### 4. Explore a dominator subtree

```bash
printf 'ALL RETAINED BY i<id> TOP 20 BY retainedSize\n' | heapo open --output jsonl DUMP
```

Shows all objects exclusively retained by `i<id>`, sorted by retained size.

### 5. Save and reuse results across queries

```bash
{
  echo "CLASS com.example.Cache TOP 10 BY retainedSize"
  echo "CALL THAT caches"
  echo "ALL RETAINED BY i<id> IN caches TOP 10 BY retainedSize"
} | heapo open --output jsonl DUMP
```

`CALL THAT <name>` saves the current result under a name you choose. Use it in subsequent `IN <name>`, `RETAINED BY <name>`, or other filter arguments within the same session.

### 6. Filter by size threshold

```bash
printf 'CLASS java.lang.String RETAINING > 100000\n' | heapo open --output jsonl DUMP
```

## Full DSL reference

### Pipeline model

Queries compose as: **source** → zero or more **filters** → optional **display**.

The result is always stored as a bitset in THAT. Display options control what is shown, not what is stored.

**Sources** (produce a bitset of objects):

| Source | Description |
|---|---|
| `ALL` | All objects |
| `CLASS <class>` | All instances of a class (`*` and `?` wildcards supported) |
| `THAT` | Current result |
| `i<n>` | Singleton: just object `i<n>` |
| `<name>` | Named result (from `CALL THAT`) or built-in name |

**Built-in names** (usable as sources or in filter arguments without `CALL THAT`):

| Name | Contents |
|---|---|
| `GcRoots` | All GC root objects |
| `Threads` | All `java.lang.Thread` instances |
| `ClassLoaders` | All `java.lang.ClassLoader` instances |
| `SoftReferences` | All `java.lang.ref.SoftReference` instances |
| `WeakReferences` | All `java.lang.ref.WeakReference` instances |
| `PhantomReferences` | All `java.lang.ref.PhantomReference` instances |

A source is itself composable — filters chain to form a new source:
```
ALL RETAINED BY i123 REFERENCING i456 TOP 10
```

**Filters** (narrow the bitset):

| Filter | Description |
|---|---|
| `IN <source>` | Bitset AND — keep objects present in source |
| `NOT IN <source>` | Bitset AND-NOT — exclude objects present in source |
| `RETAINED BY <source>` | Keep objects dominated (exclusively retained) by any object in source |
| `RETAINING > <bytes>` | Keep objects whose retained size satisfies the comparison (`>` `>=` `<` `<=` `=`) |
| `OF TYPE <class>` | Keep objects whose runtime type is `class` or any subclass |
| `OF TYPE EXACTLY <class>` | Keep objects whose runtime type is exactly `class` (no subclasses) |
| `SIZED > <bytes>` | Keep objects whose shallow (own field) size satisfies the comparison |
| `REFERENCING <source>` | Keep objects that have a direct outgoing reference to any object in source |
| `REFERENCED BY <source>` | Keep objects directly referenced (pointed to) by any object in source |
| `REACHABLE FROM <source>` | Keep objects transitively reachable (by following forward refs) from any object in source |
| `WHERE <field> <op> <value>` | Keep objects whose primitive field satisfies the comparison (`>` `>=` `<` `<=` `=`). Class context required (use `CLASS <class>` or add `OF TYPE <class>` before WHERE). Value is a number or `true`/`false`. |

**Display options** (control what is shown; result is always a bitset in THAT):

| Display | Shows |
|---|---|
| `TOP <n> [BY retainedSize]` | Largest-N by retained size |
| `BOTTOM <n> [BY retainedSize]` | Smallest-N by retained size |
| `COUNT` | Total count |
| `MAX retainedSize` | Maximum retained size |
| `SUM retainedSize` | Sum of retained sizes |
| _(none)_ | Top 10 by retained size |

**Examples:**

```
CLASS com.example.Foo                              # all Foo instances
CLASS com.example.Foo TOP 10 BY retainedSize       # top 10 Foos
ALL TOP 20 BY retainedSize                         # top 20 across all classes
ALL RETAINED BY i1234 TOP 10                       # dominator subtree of i1234
Threads REFERENCING i456                           # threads that hold i456
ALL IN suspects NOT IN excluded TOP 20             # set difference
mySet COUNT                                        # count objects in named set
THAT TOP 5 BY retainedSize                         # re-display current result
i1234 TOP 1                                        # inspect one object
```

### Other queries

| Query | Returns |
|---|---|
| `STATUS` | Object and class count |
| `CLASSES <glob>` | Classes matching glob, sorted by instance count (use `*` for all) |
| `NAMES <glob>` | Named bitset results matching glob (use `*` for all) |
| `EXPLAIN <name>` | Show which history command produced the named result (provenance) |
| `EXPLAIN i<id>` | Dominator chain from object to GC root |

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
{"depth":0,"id":"i12345","type":"java.util.HashMap","retainedSize":2097152}
{"depth":1,"id":"i99","type":"com.example.Cache","field":"table","retainedSize":8388608}
{"depth":2,"id":"i7","type":"java.lang.Class","field":"cache","retainedSize":16777216,"description":"com.example.Cache.class","notes":"GC root (system class)"}
```

The optional `field` names the field in this object that directly references the object on the prior line (`"[N]"` for array slot N, `"(indirect)"` if the dominator has no direct edge). The optional `description` field provides a human-readable summary of the object's value (same as in TOP/BOTTOM rows). The optional `notes` field carries extra context such as GC root type.

**CLASSES rows:**
```json
{"id":"i88","className":"java.util.HashMap","instanceCount":4201}
```

**COUNT:**
```json
{"count":182340}
```

**MAX / SUM:**
```json
{"className":"(pipeline)","func":"SUM","retainedSize":41943040}
```

## Analysis guidelines

- **retainedSize** is the memory freed if the object were collected; it includes all objects exclusively dominated by it. This is the primary metric for leak analysis.
- **shallowSize** is the object's own field storage only — not what it transitively points to.
- Convert byte counts to MB (divide by 1 048 576) when presenting to the user.
- Group findings by root cause (e.g. "two Cache instances together hold 450 MB").
- For leak analysis: find objects with large retained sizes, then use EXPLAIN to find what is preventing their collection.
