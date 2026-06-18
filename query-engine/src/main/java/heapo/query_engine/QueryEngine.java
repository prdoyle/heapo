package heapo.query_engine;

import heapo.indexes.IndexRegistry;
import heapo.model.*;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.util.*;
import java.util.Arrays;

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
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
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

    /**
     * Returns the bottom {@code n} objects of the given class ordered by retained size ascending.
     */
    public static List<TopNRow> allBottomByRetainedSize(UnpackedHeap heap, IndexRegistry registry,
                                                         String className, int n) throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);
        if (!allObjs && classDenseId < 0) return List.of();

        // Min-heap of size n (keep smallest retained sizes); use rank as proxy for size
        PriorityQueue<int[]> bottomN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(a[1], b[1])); // min-heap by rank

        try (var il   = registry.openInstanceList();
             var rank = registry.openRetainedSizeRank()) {

            if (allObjs) {
                for (int v = 0; v < objectCount; v++) {
                    bottomN.offer(new int[]{v, rank.readInt(v)});
                    if (bottomN.size() > n) bottomN.poll();
                }
            } else {
                for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++) {
                    int v = il.edge(e);
                    bottomN.offer(new int[]{v, rank.readInt(v)});
                    if (bottomN.size() > n) bottomN.poll();
                }
            }
        }

        // Sort by rank descending (smallest retained last = ascending retained order → rank desc = size asc)
        List<int[]> sorted = new ArrayList<>(bottomN);
        sorted.sort((a, b) -> Integer.compare(b[1], a[1])); // rank desc → retained size asc

        List<TopNRow> rows = new ArrayList<>(sorted.size());
        try (var retained    = registry.openRetainedSize();
             var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {

            for (int i = 0; i < sorted.size(); i++) {
                int v        = sorted.get(i)[0];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    /**
     * Returns all instances of the given class whose retained size satisfies the comparison.
     * Results are sorted by retained size descending.
     *
     * @param op one of {@code ">"}, {@code ">="}, {@code "<"}, {@code "<="}, {@code "="}
     */
    public static List<TopNRow> allRetaining(UnpackedHeap heap, IndexRegistry registry,
                                              String className, String op, long threshold)
            throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);
        if (!allObjs && classDenseId < 0) return List.of();

        var matching = new ArrayList<int[]>(); // [denseId, retainedHigh, retainedLow] — store as long pair
        var matchingLong = new ArrayList<long[]>(); // [denseId, retainedSize]

        try (var il       = registry.openInstanceList();
             var retained = registry.openRetainedSize()) {

            if (allObjs) {
                for (int v = 0; v < objectCount; v++) {
                    long rs = retained.readLong(v);
                    if (matches(rs, op, threshold)) matchingLong.add(new long[]{v, rs});
                }
            } else {
                for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++) {
                    int v  = il.edge(e);
                    long rs = retained.readLong(v);
                    if (matches(rs, op, threshold)) matchingLong.add(new long[]{v, rs});
                }
            }
        }

        matchingLong.sort((a, b) -> Long.compare(b[1], a[1])); // descending retained size

        List<TopNRow> rows = new ArrayList<>(matchingLong.size());
        try (var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {

            for (int i = 0; i < matchingLong.size(); i++) {
                int v        = (int) matchingLong.get(i)[0];
                long rs      = matchingLong.get(i)[1];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
                    rs, (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    private static boolean matches(long value, String op, long threshold) {
        return switch (op) {
            case ">"  -> value >  threshold;
            case ">=" -> value >= threshold;
            case "<"  -> value <  threshold;
            case "<=" -> value <= threshold;
            case "="  -> value == threshold;
            default   -> throw new IllegalArgumentException("Unknown op: " + op);
        };
    }

    /**
     * Computes MAX or SUM of retained sizes across all instances of the given class.
     *
     * @param func {@code "MAX"} or {@code "SUM"}
     */
    public static long aggregateRetainedSize(UnpackedHeap heap, IndexRegistry registry,
                                              String className, String func) throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);
        if (!allObjs && classDenseId < 0) return 0;

        long acc = func.equals("SUM") ? 0 : Long.MIN_VALUE;

        try (var il       = registry.openInstanceList();
             var retained = registry.openRetainedSize()) {

            if (allObjs) {
                for (int v = 0; v < objectCount; v++) {
                    long rs = retained.readLong(v);
                    acc = func.equals("SUM") ? acc + rs : Math.max(acc, rs);
                }
            } else {
                for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++) {
                    int v  = il.edge(e);
                    long rs = retained.readLong(v);
                    acc = func.equals("SUM") ? acc + rs : Math.max(acc, rs);
                }
            }
        }

        return func.equals("MAX") && acc == Long.MIN_VALUE ? 0 : acc;
    }

    /**
     * Returns all objects in the dominator subtree rooted at {@code rootDenseId}.
     * If {@code topN > 0}, only the top-N by retained size are returned; otherwise all.
     * Results are sorted by retained size descending.
     */
    public static List<TopNRow> dominatorSubtree(UnpackedHeap heap, IndexRegistry registry,
                                                   int rootDenseId, int topN) throws IOException {
        var names       = ClassNameIndex.load(heap);
        int objectCount = heap.objectCount();

        // Build reverse idom map (children list) from a single scan of idom[]
        var children = new ArrayList<List<Integer>>(objectCount);
        for (int i = 0; i < objectCount; i++) children.add(new ArrayList<>());

        try (var idom = registry.openIdom()) {
            for (int v = 0; v < objectCount; v++) {
                int parent = idom.readInt(v);
                if (parent >= 0 && parent < objectCount) {
                    children.get(parent).add(v);
                }
            }
        }

        // BFS from rootDenseId
        var subtree  = new ArrayList<Integer>();
        var queue    = new ArrayDeque<Integer>();
        queue.add(rootDenseId);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            subtree.add(cur);
            queue.addAll(children.get(cur));
        }

        // Collect [denseId, retainedSize] and sort descending
        var pairs = new ArrayList<long[]>(subtree.size());
        try (var retained = registry.openRetainedSize()) {
            for (int v : subtree) {
                pairs.add(new long[]{v, retained.readLong(v)});
            }
        }
        pairs.sort((a, b) -> Long.compare(b[1], a[1]));

        int limit = topN > 0 ? Math.min(topN, pairs.size()) : pairs.size();
        List<TopNRow> rows = new ArrayList<>(limit);
        try (var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {

            for (int i = 0; i < limit; i++) {
                int v        = (int) pairs.get(i)[0];
                long rs      = pairs.get(i)[1];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
                    rs, (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    /**
     * Builds a bitset of all instances of {@code className} (or all objects if {@code "*"}).
     * Bit {@code v} is set iff dense ID {@code v} is in the result set.
     */
    public static long[] buildBitSet(UnpackedHeap heap, IndexRegistry registry,
                                      String className) throws IOException {
        var names        = ClassNameIndex.load(heap);
        int objectCount  = heap.objectCount();
        boolean allObjs  = className.equals("*");
        int classDenseId = allObjs ? -1 : names.resolve(className);

        int words  = (objectCount + 63) >>> 6;
        long[] bits = new long[words];

        if (allObjs) {
            Arrays.fill(bits, -1L);
            int rem = objectCount & 63;
            if (rem != 0) bits[words - 1] = (1L << rem) - 1;
        } else {
            if (classDenseId < 0) return bits; // class not found → empty
            try (var il = registry.openInstanceList()) {
                for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++) {
                    int v = il.edge(e);
                    bits[v >>> 6] |= 1L << (v & 63);
                }
            }
        }
        return bits;
    }

    /** Returns the number of set bits in {@code bits}. */
    public static int bitSetCardinality(long[] bits) {
        int count = 0;
        for (long word : bits) count += Long.bitCount(word);
        return count;
    }

    /**
     * Returns the top {@code n} objects in the bitset ordered by retained size descending.
     */
    public static List<TopNRow> topNFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                long[] bits, int n) throws IOException {
        var names       = ClassNameIndex.load(heap);
        int objectCount = heap.objectCount();

        PriorityQueue<int[]> topN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(b[1], a[1])); // max-heap by rank

        try (var rank = registry.openRetainedSizeRank()) {
            for (int v = 0; v < objectCount; v++) {
                if ((bits[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                    topN.offer(new int[]{v, rank.readInt(v)});
                    if (topN.size() > n) topN.poll();
                }
            }
        }

        List<int[]> sorted = new ArrayList<>(topN);
        sorted.sort(Comparator.comparingInt(a -> a[1]));

        List<TopNRow> rows = new ArrayList<>(sorted.size());
        try (var retained    = registry.openRetainedSize();
             var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {
            for (int i = 0; i < sorted.size(); i++) {
                int v        = sorted.get(i)[0];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    /**
     * Returns the bottom {@code n} objects in the bitset ordered by retained size ascending.
     */
    public static List<TopNRow> bottomNFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                   long[] bits, int n) throws IOException {
        var names       = ClassNameIndex.load(heap);
        int objectCount = heap.objectCount();

        // Keep N objects with the LARGEST ranks (= smallest retained sizes).
        // Min-heap keyed by rank: pops smallest rank (= largest retained) when overfull.
        PriorityQueue<int[]> bottomN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(a[1], b[1]));

        try (var rank = registry.openRetainedSizeRank()) {
            for (int v = 0; v < objectCount; v++) {
                if ((bits[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                    bottomN.offer(new int[]{v, rank.readInt(v)});
                    if (bottomN.size() > n) bottomN.poll();
                }
            }
        }

        // rank desc = retained size asc
        List<int[]> sorted = new ArrayList<>(bottomN);
        sorted.sort((a, b) -> Integer.compare(b[1], a[1]));

        List<TopNRow> rows = new ArrayList<>(sorted.size());
        try (var retained    = registry.openRetainedSize();
             var shallowSize = registry.openShallowSize();
             var classOf     = registry.openClassOf()) {
            for (int i = 0; i < sorted.size(); i++) {
                int v        = sorted.get(i)[0];
                int classDid = classOf.readInt(v);
                rows.add(new TopNRow(i + 1, v, names.nameOf(classDid),
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L));
            }
        }
        return rows;
    }

    /** Computes MAX or SUM of retained sizes over all set bits in {@code bits}. */
    public static long aggregateFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                            long[] bits, String func) throws IOException {
        int objectCount = heap.objectCount();
        long acc = func.equals("SUM") ? 0 : Long.MIN_VALUE;
        try (var retained = registry.openRetainedSize()) {
            for (int v = 0; v < objectCount; v++) {
                if ((bits[v >>> 6] >>> (v & 63) & 1L) != 0L) {
                    long rs = retained.readLong(v);
                    acc = func.equals("SUM") ? acc + rs : Math.max(acc, rs);
                }
            }
        }
        return func.equals("MAX") && acc == Long.MIN_VALUE ? 0 : acc;
    }

    private static boolean matchGlob(String s, String pattern) {
        return s.matches(pattern.replace(".", "\\.").replace("*", ".*").replace("?", "."));
    }
}
