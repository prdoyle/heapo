package heapo.query_engine;

import heapo.indexes.IndexRegistry;
import heapo.model.*;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.util.*;

/**
 * Executes heap queries against a built index set.
 */
public final class QueryEngine {

    private QueryEngine() {}

    /**
     * Returns the top {@code n} objects of the given class ordered by retained size descending.
     *
     * @param className fully-qualified dotted class name, or {@code "*"} for all objects
     */
    public static List<TopNRow> allTopByRetainedSize(UnpackedHeap heap, IndexRegistry registry,
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
        List<TopNRow> rows = new ArrayList<>(sorted.size());
        try (var retained    = registry.openRetainedSize();
             var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {

            for (int i = 0; i < sorted.size(); i++) {
                int v        = sorted.get(i)[0];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i, v, names.nameOf(classDid),
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    /** Count of direct instances of the given class (or total objects if {@code "*"}). */
    public static long aggregateCount(UnpackedHeap heap, IndexRegistry registry,
                                       String className) throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);
        if (!allObjs && classDenseId < 0) return 0;

        if (allObjs) return objectCount;
        try (var il = registry.openInstanceList()) {
            return il.degree(classDenseId);
        }
    }

    /**
     * Returns a list of classes whose names match the optional glob pattern.
     * Each entry includes the instance count. Results are sorted by instance count descending.
     */
    public static List<ClassInfo> classes(UnpackedHeap heap, IndexRegistry registry,
                                           String glob) throws IOException {
        var names = ClassNameIndex.load(heap);
        var result = new ArrayList<ClassInfo>();

        try (var il = registry.openInstanceList()) {
            for (String slashedName : names.allSlashedNames()) {
                String dottedName = slashedName.replace('/', '.');
                if (glob != null && !matchGlob(dottedName, glob)) continue;
                int classDenseId = names.resolve(dottedName);
                if (classDenseId < 0) continue;
                long count = il.degree(classDenseId);
                result.add(new ClassInfo(classDenseId, dottedName, count));
            }
        }

        result.sort(Comparator.comparingLong(ClassInfo::instanceCount).reversed()
            .thenComparing(ClassInfo::className));
        return result;
    }

    /**
     * Walks the idom[] chain from {@code denseId} up to the GC root, returning the path.
     * Index 0 = the queried object; last entry = a direct child of the super-root (no idom).
     */
    public static List<ExplainNode> explain(UnpackedHeap heap, IndexRegistry registry,
                                             int denseId) throws IOException {
        var names = ClassNameIndex.load(heap);
        var path  = new ArrayList<ExplainNode>();

        try (var idom     = registry.openIdom();
             var retained = registry.openRetainedSize();
             var classOf  = registry.openClassOf()) {

            int cur   = denseId;
            int depth = 0;
            while (cur >= 0 && cur < heap.objectCount()) {
                int     classDid     = classOf.readInt(cur);
                String  className    = names.nameOf(classDid);
                long    retainedSize = retained.readLong(cur);
                path.add(new ExplainNode(cur, className, retainedSize, depth++));
                cur = idom.readInt(cur); // -1 = root of dominator tree
            }
        }
        return path;
    }

    private static boolean matchGlob(String s, String pattern) {
        return s.matches(pattern.replace(".", "\\.").replace("*", ".*").replace("?", "."));
    }
}
