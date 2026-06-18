# heapo — Human Setup Guide

heapo analyses JVM heap dumps (.hprof files) via dominator tree. Give it a
heap dump and ask what's retaining the most memory; drill into specific objects
or classes; pipe results through SQL.

**Not sure where you're at?** Run `./install.sh --status` to see what's done
and what remains.

## Install

```bash
/path/to/heapo/install.sh --go
```

Requires Java 25+ on your PATH. The script builds heapo, symlinks it to
`~/.local/bin/heapo`, installs the Claude skill, and adds a Claude Code
allowlist entry. It is safe to re-run — steps already complete are skipped.

## Get a heap dump

Any JVM can produce one on demand:

```bash
jmap -dump:format=b,file=heap.hprof <pid>
```

Or pass `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/` to the JVM
to get one automatically on OOM.

## Explore interactively

```bash
heapo open heap.hprof
```

Indexes are built on the first run (seconds to a few minutes depending on heap
size) and cached in `heap.hprof.d/`. Subsequent `open` or `query` calls reuse
the cache and start immediately.

Type DSL queries at the `heapo>` prompt. Tab completion suggests keywords and
class names. `exit` or Ctrl-D to quit.

## Run a single query

```bash
heapo query heap.hprof ALL * TOP 20 BY retainedSize
heapo query heap.hprof --output human ALL * TOP 20 BY retainedSize
```

`--output human` formats results as an aligned table. See `heapo query --help`
for all output options.

## Pre-build indexes

```bash
heapo unpack heap.hprof
```

Useful when you want to pay the indexing cost upfront (e.g. in CI) before
running many queries.

## Claude Code setup

`install.sh --go` adds `Bash(heapo *)` to `~/.claude/settings.json` and
installs the Claude skill at `~/.claude/skills/heapo/SKILL.md`. After that,
LLM agents can run `heapo query` without a permission prompt on every call and
know the full DSL from the skill.

Run `./install.sh --status` to confirm both are in place.

## Run tests

```bash
./gradlew test
```
