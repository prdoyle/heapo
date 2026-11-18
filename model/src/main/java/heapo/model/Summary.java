package heapo.model;

/**
 * Aggregate snapshot of a heap dump.
 *
 * <p>Byte counts use the same 8-byte-reference model as {@code shallowSize}, so they are
 * comparable with each other but not with {@code -Xmx}.
 *
 * @param unreachableObjectCount objects not reachable from any GC root at dump time; mainly
 *                               present when the dump was taken without a preceding collection
 *                               (e.g. {@code HeapDumpOnOutOfMemoryError})
 */
public record Summary(long objectCount, int classCount,
                      long reachableObjectCount, long unreachableObjectCount,
                      long reachableBytes, long unreachableBytes) {}
