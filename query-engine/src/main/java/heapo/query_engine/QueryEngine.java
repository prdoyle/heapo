package heapo.query_engine;

import heapo.indexes.IndexRegistry;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.util.*;

/**
 * Executes heap queries against a built index set.
 * Phase 4: only {@code ALL <class> TOP n BY retainedSize} is supported.
 */
public final class QueryEngine {

    /** A single row in a TOP-N query result. */
    public record Row(int rank, int denseId, String className,
                      long retainedSize, long shallowSize) {}

    private QueryEngine() {}

    /**
     * Returns the top {@code n} objects of the given class ordered by retained size descending.
     *
     * @param className fully-qualified dotted class name, or {@code "*"} for all objects
     */
    public static List<Row> allTopByRetainedSize(UnpackedHeap heap, IndexRegistry registry,
                                                  String className, int n) throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);
        if (!allObjs && classDenseId < 0) return List.of(); // class not found

        // Collect (denseId, rank) pairs, keeping the top-N smallest ranks (= largest retained sizes).
        // Use a max-heap of size N: pop the max when over capacity.
        PriorityQueue<int[]> topN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(b[1], a[1])); // max-heap by rank

        try (var il   = registry.openInstanceList();
             var rank = registry.openRetainedSizeRank()) {

            if (allObjs) {
                for (int v = 0; v < objectCount; v++) {
                    topN.offer(new int[]{v, rank.readInt(v)});
                    if (topN.size() > n) topN.poll();
                }
            } else {
                for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++) {
                    int v = il.edge(e);
                    topN.offer(new int[]{v, rank.readInt(v)});
                    if (topN.size() > n) topN.poll();
                }
            }
        }

        // Sort collected results by rank ascending (largest retained first)
        List<int[]> sorted = new ArrayList<>(topN);
        sorted.sort(Comparator.comparingInt(a -> a[1]));

        // Build result rows
        List<Row> rows = new ArrayList<>(sorted.size());
        try (var retained    = registry.openRetainedSize();
             var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {

            for (int i = 0; i < sorted.size(); i++) {
                int v        = sorted.get(i)[0];
                int classDid = classOf.readInt(v);
                rows.add(new Row(i, v, names.nameOf(classDid),
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }
}
