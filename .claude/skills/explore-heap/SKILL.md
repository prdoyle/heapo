---
description: Analyse a Java heap dump using heapo. Use when the user wants to explore memory usage, find memory leaks, identify large retained objects, or investigate a JVM heap dump (.hprof file).
when_to_use: Triggered by phrases like: heap dump, HPROF file, memory leak, OutOfMemoryError, retained size, dominator tree, OOM, what's eating memory, heap analysis, large objects, heap exploration.
argument-hint: [path/to/dump.hprof]
---

!`./gradlew --quiet :cli:installDist > /dev/null 2>&1 && ./cli/build/install/heapo/bin/heapo --skill`
