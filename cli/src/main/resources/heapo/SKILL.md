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

**On a large heap dump, run `heapo unpack` before querying** — it builds all indexes up front with progress output, so you can see it working. On large dumps this can take several minutes.

```bash
heapo unpack DUMP
```

Progress is printed to stderr. Once it exits, indexes are cached on disk and subsequent runs are instant.

**Then use `heapo open --output jsonl` with a heredoc for multi-step analysis.** This gives you a stateful session (CALL THAT, named results, THAT as a source) and machine-readable output. Each command goes on its own line; output for each command follows immediately before the next command runs.

```bash
heapo open --output jsonl DUMP << 'EOF'
STATUS
SHOW 10 BY retainedSize
EOF
```

Use `heapo query` only for truly one-shot lookups when no session state is needed.

Replace `DUMP` with the actual path to the `.hprof` file in all commands below.

### 1. Orient

```bash
heapo open --output jsonl DUMP << 'EOF'
STATUS
CLASSES heapo.*
EOF
```

### 2. Find the biggest memory consumers

```bash
heapo open --output jsonl DUMP << 'EOF'
SHOW 20 BY retainedSize
EOF
```

Identify the classes contributing the most retained memory, then drill in:

```bash
heapo open --output jsonl DUMP << 'EOF'
CLASS com.example.Foo SHOW 10 BY retainedSize
CLASS com.example.Foo SUM retainedSize
EOF
```

### 3. Trace what is holding an object in memory

```bash
heapo open --output jsonl DUMP << 'EOF'
EXPLAIN i<id>
EOF
```

Each line is the immediate dominator — the object that, if collected, would free everything below it. Walk the chain upward to find the GC root preventing collection. The optional `field` names the field in this object that directly references the object on the prior line.

### 4. Explore a dominator subtree

```bash
heapo open --output jsonl DUMP << 'EOF'
ALL RETAINED BY i<id> SHOW 20 BY retainedSize
EOF
```

Shows all objects exclusively retained by `i<id>`, sorted by retained size.

### 5. Save and reuse results across queries

```bash
heapo open --output jsonl DUMP << 'EOF'
CLASS com.example.Cache SHOW 10 BY retainedSize
CALL THAT caches
ALL RETAINED BY i<id> IN caches SHOW 10 BY retainedSize
EOF
```

`CALL THAT <name>` saves the current result under a name you choose. Use it in subsequent `IN <name>`, `RETAINED BY <name>`, or other filter arguments within the same session.

### 6. Filter by size threshold

```bash
heapo open --output jsonl DUMP << 'EOF'
CLASS java.lang.String RETAINING > 100000
EOF
```

## Full DSL reference

### Pipeline model

Queries compose as: **source** → zero or more **filters** → optional **terminal**.

The result is stored as a bitset in THAT. `TOP N` truncates THAT to the N largest objects; all other terminals leave THAT as the full filtered set.

**Sources** (produce a bitset of objects):

| Source | Description |
|---|---|
| `ALL` | All objects |
| `CLASS <class>` | All instances of a class (`*` wildcard supported) |
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
| `WHERE <field> <op> <value>` | Keep objects whose primitive field satisfies the comparison. Class context required (use `CLASS <class>` or add `OF TYPE <class>` before WHERE). Value is a number or `true`/`false`. |
| `WHERE <field> = <pattern>` | Keep objects whose String field matches a pattern. Requires `=`. `"exact"` (exact match), `"prefix"*` (startsWith), `*"suffix"` (endsWith), `*"sub"*` (contains). No spaces or backslashes inside quotes. Example: `WHERE name = *"write"*` |

**Terminals** (end the pipeline):

| Terminal | Description |
|---|---|
| `TOP <n> [BY retainedSize]` | Narrow THAT to the N largest objects and display them |
| `SHOW <n> [BY retainedSize]` | Display the N largest without changing THAT |
| `SAMPLE <n>` | Display N randomly sampled objects (reservoir sampling) |
| `COUNT` | Total count |
| `MAX retainedSize` | Maximum retained size |
| `SUM retainedSize` | Sum of retained sizes |
| _(none)_ | Display top 10 by retained size |

Use `TOP` when you want a smaller set to filter against or anchor a chain. Use `SHOW` when you just want to see N rows but keep the full set in THAT for follow-up queries.

**Examples:**

```
CLASS com.example.Foo                              # all Foo instances
CLASS com.example.Foo SHOW 10 BY retainedSize      # display top 10 Foos, keep all in THAT
CLASS com.example.Foo TOP 10 BY retainedSize       # narrow THAT to the 10 largest Foos
SHOW 20 BY retainedSize                            # display top 20 across all classes
ALL RETAINED BY i1234 SHOW 10                      # display dominator subtree of i1234
Threads REFERENCING i456                           # threads that hold i456
ALL IN suspects NOT IN excluded SHOW 20            # set difference (display 20)
mySet COUNT                                        # count objects in named set
THAT SHOW 5 BY retainedSize                        # display 5 rows from current THAT
i1234 TOP 1                                        # THAT becomes just i1234 (useful as anchor)
```

### Other queries

| Query | Returns |
|---|---|
| `STATUS` | Object and class count |
| `CLASSES <glob>` | Classes matching glob, sorted by instance count (use `*` for all) |
| `NAMES [<glob>]` | Named results; glob filters by name (omit for all) |
| `EXPLAIN <name>` | Show which history command produced the named result (provenance) |
| `EXPLAIN i<id>` | Dominator chain from object to GC root |
| `INSPECT i<id>` | Show all fields of object i<id> |

## Output formats

| Flag | Use when |
|---|---|
| `--output human` | Default; aligned table for human reading |
| `--output jsonl` | One JSON object per line — use this as an LLM |
| `--output json` | Full JSON array |

Both `heapo open` and `heapo query` accept `--output`.

## JSONL field reference

**TOP / SHOW / RETAINING / RETAINED BY rows:**
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

The optional `field` names the field in this object that directly references the object on the prior line (`"[N]"` for array slot N, `"(indirect)"` if the dominator has no direct edge). The optional `description` field provides a human-readable summary of the object's value (same as in TOP/SHOW rows). The optional `notes` field carries extra context such as GC root type.

**INSPECT rows** (one row per field, in declaration order):
```json
{"field":"name","id":"i99","type":"java.lang.String","retainedSize":24,"shallowSize":24,"description":"write"}
{"field":"handler","id":"null"}
{"field":"maximumPoolSize","type":"int","description":"4"}
```

Object reference fields emit `id`, `type`, `retainedSize`, `shallowSize`, and optionally `description`. Null references emit only `id: "null"`. Primitive fields emit `type` (the Java type name) and `description` (the formatted value). For object arrays, field names are `[0]`, `[1]`, … (non-null elements only). For primitive arrays, field names are `[0]`, `[1]`, … capped at 100 elements.

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

## Presenting conclusions

Every conclusion presented to the user must be accompanied by the command(s) that support it and their output. This lets the user verify independently and builds trust in the analysis.

Show only the DSL commands — not the full `heapo open` invocation — since the user already has the dump open and can run them directly. Obtain human-readable output by running a separate `--output human` invocation yourself; do not show raw JSONL to the user.

Format: state the conclusion, then show the evidence inline:

```
The cache is retaining 450 MB — two instances, one per shard.

  CLASS com.example.Cache SHOW 5 BY retainedSize
   rank  id      type                retained   shallow
      1  i4521   com.example.Cache   225.1 MB    48 B
      2  i4888   com.example.Cache   224.8 MB    48 B
```

If output is long, abbreviate it — show the first few rows, add `(… N more)`, and summarise what the omitted rows contain. Never silently drop evidence; always acknowledge what was cut.

For multi-step chains of reasoning, show the command and output at each step rather than just the final conclusion.
