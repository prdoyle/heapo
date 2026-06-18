You are helping the user analyse a Java heap dump using **heapo**, a CLI tool
in this repository that analyses HPROF files via dominator tree.

## Building heapo

```
./gradlew :cli:installDist
```

This produces `cli/build/install/heapo/bin/heapo`. Run it from there, or add
it to PATH. All subsequent examples use `heapo` as the command name.

## Invocation modes

**Interactive REPL** (recommended for exploration):
```
heapo dump.hprof --explore
```
Builds all indexes upfront (takes a few seconds the first time), then opens
a prompt. Indexes are cached in `dump.hprof.d/` and reused on subsequent runs.

**Single query and exit**:
```
heapo dump.hprof --quick "ALL * TOP 20 BY retainedSize"
heapo dump.hprof --output human ALL * TOP 20 BY retainedSize
```

**Output formats**: `--output jsonl` (default), `--output json` (JSON array),
`--output human` (aligned table). Only applies to single-query mode; the REPL
always outputs JSONL.

**Custom index directory**:
```
heapo dump.hprof -d /fast-ssd/my-heap --explore
```

## REPL commands (full reference)

### Exploration

| Command | Description |
|---|---|
| `STATUS` | Object and class count in the dump |
| `CLASSES [MATCHING <glob>]` | List classes with instance counts; glob matches on dotted class name |
| `ALL <class> TOP <n> BY retainedSize` | Top-N instances by retained size (largest first) |
| `ALL <class> BOTTOM <n> BY retainedSize` | Bottom-N instances by retained size (smallest first) |
| `ALL <class> RETAINING > <bytes>` | All instances whose retained size satisfies the comparison (`>` `>=` `<` `<=` `=`) |
| `ALL <class> AGGREGATE COUNT` | Count of direct instances |
| `ALL <class> AGGREGATE MAX retainedSize` | Maximum retained size across all instances |
| `ALL <class> AGGREGATE SUM retainedSize` | Sum of retained sizes across all instances |
| `EXPLAIN #<id>` | Walk the dominator chain from object `#id` up to the GC root |
| `DOMINATOR SUBTREE OF #<id>` | All objects in the dominator subtree rooted at `#id`, by retained size |
| `DOMINATOR SUBTREE OF #<id> TOP <n> BY retainedSize` | Same, limited to top N |

Use `*` as the class name to query across all objects:
```
ALL * TOP 10 BY retainedSize
ALL * AGGREGATE COUNT
ALL * RETAINING >= 1000000
```

### Session management

| Command | Description |
|---|---|
| `CALL THAT <name>` | Name the most recent result set |
| `CALL #<id> <name>` | Name a specific history entry by id |
| `FORGET <name>` | Remove a name binding |
| `UNDO` | Reverse the most recent CALL or FORGET |
| `NAMES` | List all current name bindings |
| `HISTORY [n]` | Show last n commands (default 10) |

### SQL

Any input starting with `SELECT` or `WITH` is routed to SQLite and runs
against the session database, which holds all materialised result tables.
After naming a result with `CALL THAT foo`, you can query it directly:

```
CALL THAT big_strings
SELECT * FROM big_strings WHERE retainedSize > 500000
SELECT className, COUNT(*) FROM big_strings GROUP BY className ORDER BY 2 DESC
```

## JSONL output format

Each result line is a JSON object. Key fields:

**TOP / BOTTOM / RETAINING / DOMINATOR SUBTREE results:**
```json
{"rank":0,"id":"#12345","type":"java.lang.String","retainedSize":2097152,"shallowSize":24}
```

**CLASSES results:**
```json
{"id":"#88","className":"java.util.HashMap","instanceCount":4201}
```

**EXPLAIN results** (index 0 = the object, last = closest to GC root):
```json
{"depth":0,"id":"#12345","type":"java.lang.String","retainedSize":2097152}
{"depth":1,"id":"#99","type":"com.example.Cache","retainedSize":8388608}
```

**AGGREGATE COUNT:**
```json
{"className":"java.lang.String","count":182340}
```

**AGGREGATE MAX/SUM:**
```json
{"className":"java.lang.String","func":"SUM","retainedSize":41943040}
```

**STATUS:**
```json
{"objectCount":1482903,"classCount":12441}
```

## Typical investigation workflow

### 1 — Orient yourself
```
STATUS
CLASSES MATCHING java.util.*
ALL * TOP 20 BY retainedSize
```

### 2 — Drill into a suspicious class
```
ALL com.example.SomeCache TOP 5 BY retainedSize
CALL THAT big_caches
ALL com.example.SomeCache AGGREGATE SUM retainedSize
```

### 3 — Find what's holding an object in memory
```
EXPLAIN #12345
```
Follow the chain from the object up to the GC root. The dominator at each
level is the object that, if collected, would free everything below it.

### 4 — Explore a subtree
```
DOMINATOR SUBTREE OF #99 TOP 20 BY retainedSize
```

### 5 — Filter and compare with SQL
```
ALL java.lang.String RETAINING > 100000
CALL THAT big_strings
SELECT className, COUNT(*), MAX(retainedSize) FROM big_strings GROUP BY className
```

### 6 — Bookmark findings
```
CALL THAT suspect_caches       -- name the current result
CALL #3 baseline               -- name an older result by history id
HISTORY 20                     -- review what you've run
UNDO                           -- undo the last CALL/FORGET if you misnamed
```

## Tips

- Dense IDs (`#12345`) are stable within one unpack run but change if the HPROF
  is re-unpacked. Use names (CALL THAT) when referencing objects across sessions.
- `retainedSize` is the memory freed if the object were collected — it includes
  all objects that are exclusively dominated by it. This is usually what you
  care about for memory leak analysis.
- `shallowSize` is the object's own footprint (fields only, no header).
- For very large heaps, build indexes once with `--explore` and then re-enter
  the REPL as needed — the cached index directory (`dump.hprof.d/`) makes
  subsequent startups fast.
- Tab completion is available in the REPL for keywords, class names, and names.
