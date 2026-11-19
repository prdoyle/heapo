package heapo.model;

/**
 * Aggregate snapshot of a heap dump.
 *
 * <p>Byte counts use the same 8-byte-reference model as {@code shallowSize}, so they are
 * comparable with each other but not with {@code -Xmx}.
 *
 * @param unreachableObjectCount objects not reachable from any GC root at dump time. This is not
 *                               "would be collected": HPROF omits class objects' instance fields
 *                               and non-bootstrap class mirrors, so a small residue remains even
 *                               after a full collection.
 */
public record Summary(long objectCount, int classCount,
                      long reachableObjectCount, long unreachableObjectCount,
                      long reachableBytes, long unreachableBytes) {}
