# heapo — Use Cases

Two sources: general brainstorming and real Elasticsearch heap dump investigations.

---

## From brainstorming

### Retained heap / memory ownership
- What's eating all the memory? — top N by retained size
- Which GC roots retain the most memory?
- What's retaining a specific object — why isn't it being collected?

### Leak detection
- Objects that should have been collected but weren't
- Duplicate strings / redundant data
- Too many instances of a class

### Collection health
- Oversized collections (HashMap with 1M entries)
- Sparse collections (HashMap 5% full)
- Collections using wrong types (List of Integer instead of int[])
- Nested collections with surprising depth

### Class loader issues
- Too many class loaders
- Classes loaded multiple times by different loaders
- Leaked class loaders preventing undeployment

### Thread state
- How many threads exist?
- What are blocked/waiting threads waiting on?
- Thread-local variables holding large object graphs

### Framework-specific patterns
- Hibernate session cache holding too many entities
- Spring beans unexpectedly in memory
- Connection pools — size, checked out vs idle
- Executor/thread pool queue depths and contents
- Cache grown beyond configured bounds
- Multiple cache instances for same logical cache
- Message queue backlog in memory
- Unconsumed event listeners (observer leak)
- Request context objects not cleaned up
- Batch job materializing entire dataset instead of streaming

### Application-specific patterns
- Objects referencing something not in an "active" set — the orphaned session example
- Objects whose class belongs to a specific module or classloader
- Entries in one collection whose referenced objects are absent from another collection

---

## From Elasticsearch issue analysis

1. **What's holding the bulk of the heap?** — dominator / biggest objects. Named culprits: ElasticsearchLRUQueryCache, IndexWriter, SearchService, PageCacheRecycler, SegmentReader, FiltersAggregator
2. **Which thread / in-flight request caused it?** — thread-local and stack-frame variables on transport_worker/search threads
3. **Why didn't the circuit breaker trip?** — comparing `_nodes/stats` reported memory vs real retained heap
4. **Leak or single-shot blowup?** — monotonic growth over days vs one-request spike
5. **Class histogram** — what dominates by count × size? Counts compared to shard/segment/field cardinality
6. **Who is the GC root keeping it alive?** — path to GC root
7. **Is an ES cache/pool oversized vs configured?** — query cache, enrich cache, security automatons, Netty pools
8. **What was the request payload?** — reconstructing query DSL / bulk body / script from retained BytesRef/Strings
9. **Which index / shard / field?** — retained heap grouped by IndexService/SegmentReader/mapper/field type
10. **Heap vs direct/off-heap?** — DirectByteBuffer, Netty, LLRC, off-heap big arrays
11. **Correlate with GC logs and stacks** — old gen climbs, young GC stops freeing
12. **Meta: can I even capture a usable dump?** — gzip, path docs, -XX:OnOutOfMemoryError
