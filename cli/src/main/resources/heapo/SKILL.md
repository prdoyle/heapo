---
description: Analyse a Java heap dump using heapo. Use when the user wants to explore memory usage, find memory leaks, identify large retained objects, or investigate a JVM heap dump (.hprof file).
when_to_use: Triggered by phrases like: heap dump, HPROF file, memory leak, OutOfMemoryError, retained size, dominator tree, OOM, what's eating memory, heap analysis, large objects, heap exploration.
argument-hint: [path/to/dump.hprof]
---

Analyse the heap dump at `$ARGUMENTS`. If no path was provided, ask the user for it before proceeding.

## Setup

Build heapo if the binary is not already present:

```bash
./gradlew :cli:installDist
```

Binary location after build: `cli/build/install/heapo/bin/heapo`

## Workflow

Run individual queries non-interactively. Use `--output human` for readable terminal output; use `--output jsonl` (default) when you need to parse results programmatically.

Replace `DUMP` with the actual path to the `.hprof` file in all commands below.

### 1. Orient

```bash
heapo DUMP --output human STATUS
heapo DUMP --output human CLASSES
```

### 2. Find the biggest memory consumers

```bash
heapo DUMP --output human ALL * TOP 20 BY retainedSize
```

Identify the classes contributing most retained memory, then drill in:

```bash
heapo DUMP --output human ALL com.example.Foo TOP 10 BY retainedSize
heapo DUMP --output human ALL com.example.Foo AGGREGATE SUM retainedSize
```

### 3. Trace what is holding an object in memory

```bash
heapo DUMP --output human EXPLAIN #<id>
```

Each line is the immediate dominator — the object that, if collected, would free everything below it. Walk the chain upward to find the GC root preventing collection.

### 4. Explore a dominator subtree

```bash
heapo DUMP --output human DOMINATOR SUBTREE OF #<id> TOP 20 BY retainedSize
```

Shows all objects exclusively retained by `#<id>`, sorted by retained size.

### 5. Filter by size threshold

```bash
heapo DUMP --output human ALL java.lang.String RETAINING > 100000
```

## Full DSL reference

| Query | Returns |
|---|---|
| `STATUS` | Object and class count |
| `CLASSES [MATCHING <glob>]` | All classes sorted by instance count; glob matches dotted class name |
| `ALL <class> TOP <n> BY retainedSize` | Largest-N instances by retained size |
| `ALL <class> BOTTOM <n> BY retainedSize` | Smallest-N instances by retained size |
| `ALL <class> RETAINING > <bytes>` | Instances satisfying the comparison (`>` `>=` `<` `<=` `=`) |
| `ALL <class> AGGREGATE COUNT` | Total instance count |
| `ALL <class> AGGREGATE MAX retainedSize` | Maximum retained size across all instances |
| `ALL <class> AGGREGATE SUM retainedSize` | Sum of retained sizes across all instances |
| `EXPLAIN #<id>` | Dominator chain from object to GC root |
| `DOMINATOR SUBTREE OF #<id> [TOP <n> BY retainedSize]` | All objects in the dominator subtree |

Use `*` as the class name to query across all objects regardless of type.

## Output formats

| Flag | Use when |
|---|---|
| `--output human` | Presenting results to the user (aligned table) |
| `--output jsonl` | Parsing results in code (one JSON object per line) |
| `--output json` | Needing a JSON array |

## JSONL field reference

**TOP / BOTTOM / RETAINING / DOMINATOR SUBTREE rows:**
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
- **shallowSize** is the object's own field storage only — not what it points to.
- Convert byte counts to MB (divide by 1 048 576) when presenting to the user.
- Group findings by root cause (e.g. "two Cache instances together hold 450 MB").
- For leak analysis: find objects with large retained sizes, then use EXPLAIN to find what is preventing their collection.
- Indexes are cached in `<dump>.d/` next to the HPROF file. The first run unpacks and indexes the dump (takes seconds to minutes depending on size); subsequent runs reuse the cache and are fast.
