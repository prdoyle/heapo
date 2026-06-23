package heapo.query_engine;

import heapo.indexes.CsrReader;
import heapo.indexes.IndexFile;
import heapo.indexes.IndexRegistry;
import heapo.model.*;
import heapo.unpack.HprofReader;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.BitSet;

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
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
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
        var names     = ClassNameIndex.load(heap);
        var path      = new ArrayList<ExplainNode>();
        var classDids = new ArrayList<Integer>();

        try (var idom     = registry.openIdom();
             var retained = registry.openRetainedSize();
             var classOf  = registry.openClassOf();
             var fwd      = registry.openForwardRefs()) {

            int cur   = denseId;
            int depth = 0;
            while (cur >= 0 && cur < heap.objectCount()) {
                int    classDid  = classOf.readInt(cur);
                String className = names.nameOf(classDid);
                String notes     = null;
                if ("java.lang.Class".equals(className)) {
                    String represented = names.nameOf(cur);
                    if (!"?".equals(represented)) notes = represented + ".class";
                }
                long retainedSize = retained.readLong(cur);
                path.add(new ExplainNode(cur, className, retainedSize, depth++, notes));
                classDids.add(classDid);
                cur = idom.readInt(cur); // -1 = root of dominator tree
            }

            // Annotate each node with the field in its parent that directly references it.
            // path[i] is the child, path[i+1] is the parent (dominator).
            for (int i = 0; i < path.size() - 1; i++) {
                int    childId       = path.get(i).denseId();
                int    parentId      = path.get(i + 1).denseId();
                String parentClass   = path.get(i + 1).className();
                int    parentClassDid = classDids.get(i + 1);

                long start  = fwd.start(parentId);
                long end    = fwd.end(parentId);
                int  relPos = -1;
                for (long pos = start; pos < end; pos++) {
                    if (fwd.edge(pos) == childId) { relPos = (int)(pos - start); break; }
                }

                String via;
                if (relPos < 0) {
                    via = "(indirect)";
                } else if (parentClass.startsWith("[")) {
                    via = "[" + relPos + "]";
                } else {
                    via = objectFieldName(parentClassDid, relPos, registry);
                }

                ExplainNode node = path.get(i);
                path.set(i, new ExplainNode(node.denseId(), node.className(),
                                             node.retainedSize(), node.depth(), node.notes(), via));
            }
        }
        return path;
    }

    private static String objectFieldName(int classDenseId, int refPos, IndexRegistry registry)
            throws IOException {
        var schema = registry.loadFieldSchema(classDenseId);
        int count  = 0;
        for (var field : schema) {
            if (field.typeCode() == HprofReader.TYPE_OBJECT) {
                if (count == refPos) return field.name();
                count++;
            }
        }
        return "(indirect)";
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
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
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
                    rs, (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
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
                    rs, (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
    }

    /**
     * Builds a bitset of all instances of {@code className} (or all objects if {@code "*"}).
     * {@code className} may contain {@code *} and {@code ?} glob wildcards; if so, the union
     * of all matching classes' instance lists is returned.
     */
    public static BitSet buildBitSet(UnpackedHeap heap, IndexRegistry registry,
                                      String className) throws IOException {
        var names       = ClassNameIndex.load(heap);
        int objectCount = heap.objectCount();
        BitSet bits     = new BitSet(objectCount);

        if (className.equals("*")) {
            bits.set(0, objectCount);
            return bits;
        }

        try (var il = registry.openInstanceList()) {
            if (containsGlob(className)) {
                for (String name : names.allDottedNames()) {
                    if (matchGlob(name, className)) {
                        int classId = names.resolve(name);
                        if (classId >= 0)
                            for (long e = il.start(classId), end = il.end(classId); e < end; e++)
                                bits.set(il.edge(e));
                    }
                }
            } else {
                int classDenseId = names.resolve(className);
                if (classDenseId >= 0)
                    for (long e = il.start(classDenseId), end = il.end(classDenseId); e < end; e++)
                        bits.set(il.edge(e));
            }
        }
        return bits;
    }

    /**
     * Builds a bitset of all objects whose runtime class is {@code className} or, when
     * {@code exactly} is {@code false}, any subclass of it.
     *
     * <p>The {@code exactly=true} path is identical to {@link #buildBitSet}.
     * The {@code exactly=false} path traverses {@code super-class-of.bin} to find
     * all transitive subclasses, then unions their instance lists.
     */
    public static BitSet buildOfTypeBitSet(UnpackedHeap heap, IndexRegistry registry,
                                            String className, boolean exactly) throws IOException {
        if (exactly) return buildBitSet(heap, registry, className);

        var names = ClassNameIndex.load(heap);

        // Seed class IDs: exact match or glob expansion
        var seeds = new HashSet<Integer>();
        if (containsGlob(className)) {
            for (String name : names.allDottedNames()) {
                if (matchGlob(name, className)) {
                    int id = names.resolve(name);
                    if (id >= 0) seeds.add(id);
                }
            }
        } else {
            int id = names.resolve(className);
            if (id >= 0) seeds.add(id);
        }
        if (seeds.isEmpty()) return new BitSet(heap.objectCount());

        // Build classId → superclassId map for all known classes
        Map<Integer, Integer> parentOf = new HashMap<>();
        try (var superOf = registry.openSuperClassOf()) {
            for (int classId : names.allClassDenseIds()) {
                int superClassId = superOf.readInt(classId);
                if (superClassId != 0) parentOf.put(classId, superClassId);
            }
        }

        // BFS: collect all subclasses of any seed (including seeds themselves)
        var subclasses = new HashSet<>(seeds);
        boolean added = true;
        while (added) {
            added = false;
            for (var entry : parentOf.entrySet()) {
                if (subclasses.contains(entry.getValue()) && subclasses.add(entry.getKey()))
                    added = true;
            }
        }

        // Union instance lists of all subclasses
        BitSet bits = new BitSet(heap.objectCount());
        try (var il = registry.openInstanceList()) {
            for (int classId : subclasses) {
                for (long e = il.start(classId), end = il.end(classId); e < end; e++) {
                    bits.set(il.edge(e));
                }
            }
        }
        return bits;
    }

    /**
     * Returns a bitset of all objects that have a direct outgoing reference to any object
     * in {@code targetBits}.  Uses the reverse-reference index.
     */
    public static BitSet buildReferencingBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                 BitSet targetBits) throws IOException {
        BitSet result = new BitSet(heap.objectCount());
        try (var reverseRefs = registry.openReverseRefs()) {
            for (int v = targetBits.nextSetBit(0); v >= 0; v = targetBits.nextSetBit(v + 1)) {
                for (long e = reverseRefs.start(v), end = reverseRefs.end(v); e < end; e++) {
                    result.set(reverseRefs.edge(e));
                }
            }
        }
        return result;
    }

    /**
     * Returns a bitset of all objects directly referenced (pointed to) by any object
     * in {@code sourceBits}.  Uses the forward-reference index.
     */
    public static BitSet buildReferencedByBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                  BitSet sourceBits) throws IOException {
        BitSet result = new BitSet(heap.objectCount());
        try (var forwardRefs = registry.openForwardRefs()) {
            for (int u = sourceBits.nextSetBit(0); u >= 0; u = sourceBits.nextSetBit(u + 1)) {
                for (long e = forwardRefs.start(u), end = forwardRefs.end(u); e < end; e++) {
                    result.set(forwardRefs.edge(e));
                }
            }
        }
        return result;
    }

    /**
     * Returns a bitset of all objects transitively reachable (following forward references)
     * from any object in {@code seedBits}. The seed objects themselves are included.
     *
     * <p>This is an inclusive BFS over the reference graph — unlike {@code RETAINED BY},
     * which uses the dominator tree, reachable objects may be shared with other parts of
     * the heap and are not exclusively dominated by the seed.
     */
    public static BitSet buildReachableFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                   BitSet seedBits) throws IOException {
        BitSet visited = (BitSet) seedBits.clone();
        var frontier = new ArrayDeque<Integer>();
        for (int u = seedBits.nextSetBit(0); u >= 0; u = seedBits.nextSetBit(u + 1)) {
            frontier.add(u);
        }
        try (var forwardRefs = registry.openForwardRefs()) {
            while (!frontier.isEmpty()) {
                int u = frontier.poll();
                for (long e = forwardRefs.start(u), end = forwardRefs.end(u); e < end; e++) {
                    int v = forwardRefs.edge(e);
                    if (!visited.get(v)) {
                        visited.set(v);
                        frontier.add(v);
                    }
                }
            }
        }
        return visited;
    }

    /**
     * Returns a bitset keeping only objects from {@code inputBits} whose primitive field
     * {@code fieldName} satisfies {@code op longValue}.
     *
     * <p>{@code classDenseId} must be the dense ID of the class whose field schema to use.
     * The schema covers all primitive fields declared by that class and its ancestors.
     * {@code longValue} for booleans: 1 = true, 0 = false.
     */
    public static BitSet buildWhereFilterBitSet(
            UnpackedHeap heap, IndexRegistry registry,
            BitSet inputBits, int classDenseId,
            String fieldName, String op, long longValue) throws IOException {
        List<IndexRegistry.FieldDef> schema = registry.loadFieldSchema(classDenseId);
        IndexRegistry.FieldDef field = null;
        for (var fd : schema) {
            if (fd.name().equals(fieldName)) { field = fd; break; }
        }
        if (field == null)
            throw new IllegalArgumentException("Field '" + fieldName + "' not found in schema");

        int recordSize = IndexRegistry.fieldRecordSize(schema);
        int byteOff    = field.byteOffset();
        int typeCode   = field.typeCode();
        BitSet result  = new BitSet(heap.objectCount());

        try (var il = registry.openInstanceList();
             var fv = registry.openFieldValues(classDenseId)) {
            long start = il.start(classDenseId);
            long end   = il.end(classDenseId);
            int  idx   = 0;
            for (long e = start; e < end; e++, idx++) {
                int denseId = il.edge(e);
                if (!inputBits.get(denseId)) continue;
                long val = readPrimitive(fv, (long) idx * recordSize + byteOff, typeCode);
                if (matchesOp(val, op, longValue)) result.set(denseId);
            }
        }
        return result;
    }

    private static long readPrimitive(IndexFile fv, long byteOffset, int typeCode) {
        return switch (typeCode) {
            case HprofReader.TYPE_BOOLEAN, HprofReader.TYPE_BYTE -> fv.readByteAt(byteOffset);
            case HprofReader.TYPE_CHAR, HprofReader.TYPE_SHORT   -> fv.readShortAt(byteOffset);
            case HprofReader.TYPE_FLOAT, HprofReader.TYPE_INT    -> fv.readIntAt(byteOffset);
            case HprofReader.TYPE_DOUBLE, HprofReader.TYPE_LONG  -> fv.readLongAt(byteOffset);
            default -> 0L;
        };
    }

    private static boolean matchesOp(long value, String op, long threshold) {
        return switch (op) {
            case ">"  -> value >  threshold;
            case ">=" -> value >= threshold;
            case "<"  -> value <  threshold;
            case "<=" -> value <= threshold;
            case "="  -> value == threshold;
            default   -> false;
        };
    }

    /**
     * Returns a bitset for one of the well-known built-in names, or {@code null} if
     * {@code name} is not a built-in.
     *
     * <p>Built-in names: {@code GcRoots}, {@code Threads}, {@code ClassLoaders},
     * {@code SoftReferences}, {@code WeakReferences}, {@code PhantomReferences}.
     * Note: {@code Threads}, {@code ClassLoaders}, and reference types use exact class
     * matching — subclasses are not included.
     */
    public static BitSet buildBuiltinBitSet(UnpackedHeap heap, IndexRegistry registry,
                                             String name) throws IOException {
        return switch (name) {
            case "GcRoots" -> {
                int objectCount = heap.objectCount();
                BitSet bits = new BitSet(objectCount);
                try (var gcRoots = registry.openGcRoots()) {
                    long count = gcRoots.intCount();
                    for (long i = 0; i < count; i++) {
                        int v = gcRoots.readInt(i);
                        if (v >= 0 && v < objectCount) bits.set(v);
                    }
                }
                yield bits;
            }
            case "Threads"          -> buildBitSet(heap, registry, "java.lang.Thread");
            case "ClassLoaders"     -> buildBitSet(heap, registry, "java.lang.ClassLoader");
            case "SoftReferences"   -> buildBitSet(heap, registry, "java.lang.ref.SoftReference");
            case "WeakReferences"   -> buildBitSet(heap, registry, "java.lang.ref.WeakReference");
            case "PhantomReferences"-> buildBitSet(heap, registry, "java.lang.ref.PhantomReference");
            default                 -> null;
        };
    }

    /**
     * Expands {@code retainerBits} to include every object in the dominator subtree of
     * any object set in {@code retainerBits}.  Equivalent to the union of
     * {@link #dominatorSubtree} results for each individual retainer object.
     */
    public static BitSet buildRetainedByBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                BitSet retainerBits) throws IOException {
        BitSet result = new BitSet(heap.objectCount());
        var queue = new ArrayDeque<Integer>();
        for (int v = retainerBits.nextSetBit(0); v >= 0; v = retainerBits.nextSetBit(v + 1)) {
            result.set(v);
            queue.add(v);
        }

        try (var domChildren = registry.openDominatorChildren()) {
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                for (long e = domChildren.start(cur), end = domChildren.end(cur); e < end; e++) {
                    int child = domChildren.edge(e);
                    if (!result.get(child)) {
                        result.set(child);
                        queue.add(child);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns the top {@code n} objects in the bitset ordered by retained size descending.
     */
    public static List<TopNRow> topNFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                BitSet bits, int n) throws IOException {
        var names = ClassNameIndex.load(heap);

        PriorityQueue<int[]> topN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(b[1], a[1])); // max-heap by rank

        try (var rank = registry.openRetainedSizeRank()) {
            for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
                topN.offer(new int[]{v, rank.readInt(v)});
                if (topN.size() > n) topN.poll();
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
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
    }

    /**
     * Returns the bottom {@code n} objects in the bitset ordered by retained size ascending.
     */
    public static List<TopNRow> bottomNFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                                   BitSet bits, int n) throws IOException {
        var names = ClassNameIndex.load(heap);

        // Keep N objects with the LARGEST ranks (= smallest retained sizes).
        // Min-heap keyed by rank: pops smallest rank (= largest retained) when overfull.
        PriorityQueue<int[]> bottomN = new PriorityQueue<>(n + 1,
            (a, b) -> Integer.compare(a[1], b[1]));

        try (var rank = registry.openRetainedSizeRank()) {
            for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
                bottomN.offer(new int[]{v, rank.readInt(v)});
                if (bottomN.size() > n) bottomN.poll();
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
                    retained.readLong(v), (long) shallowSize.readInt(v) * 8L, null));
            }
        }
        return withDescriptions(rows, names, registry);
    }

    /** Computes MAX or SUM of retained sizes over all set bits in {@code bits}. */
    public static long aggregateFromBitSet(UnpackedHeap heap, IndexRegistry registry,
                                            BitSet bits, String func) throws IOException {
        long acc = func.equals("SUM") ? 0 : Long.MIN_VALUE;
        try (var retained = registry.openRetainedSize()) {
            for (int v = bits.nextSetBit(0); v >= 0; v = bits.nextSetBit(v + 1)) {
                long rs = retained.readLong(v);
                acc = func.equals("SUM") ? acc + rs : Math.max(acc, rs);
            }
        }
        return func.equals("MAX") && acc == Long.MIN_VALUE ? 0 : acc;
    }

    // ── Description enrichment ────────────────────────────────────────────────

    /**
     * Returns a new list with descriptions filled in for {@code java.lang.Class} and
     * {@code java.lang.String} objects. Class descriptions are the name of the represented class;
     * String descriptions are the string's content (Latin-1 decoded, truncated to 50 chars).
     */
    private static List<TopNRow> withDescriptions(List<TopNRow> rows, ClassNameIndex names,
                                                   IndexRegistry registry) throws IOException {
        for (int i = 0; i < rows.size(); i++) {
            TopNRow row = rows.get(i);
            if ("java.lang.Class".equals(row.className())) {
                String rep = names.nameOf(row.denseId());
                if (!"?".equals(rep)) rows.set(i, rowWithDesc(row, rep));
            }
        }

        boolean hasStrings = false;
        for (TopNRow row : rows) {
            if ("java.lang.String".equals(row.className())) { hasStrings = true; break; }
        }
        if (hasStrings && registry.hasPrimArrayIndex()) {
            try (var fwd         = registry.openForwardRefs();
                 var primOffsets = registry.openPrimArrayOffsets();
                 var primData    = registry.openPrimArrayData()) {
                for (int i = 0; i < rows.size(); i++) {
                    TopNRow row = rows.get(i);
                    if ("java.lang.String".equals(row.className())) {
                        String content = readStringContent(row.denseId(), fwd, primOffsets, primData);
                        if (content != null) rows.set(i, rowWithDesc(row, content));
                    }
                }
            }
        }

        return rows;
    }

    private static TopNRow rowWithDesc(TopNRow row, String desc) {
        return new TopNRow(row.rank(), row.denseId(), row.className(),
                           row.retainedSize(), row.shallowSize(), desc);
    }

    private static String readStringContent(int denseId, CsrReader fwd,
                                             IndexFile primOffsets, IndexFile primData) {
        long start = fwd.start(denseId);
        long end   = fwd.end(denseId);
        if (start >= end) return null;
        int byteArrayId = fwd.edge(start);

        long offset = primOffsets.readLong(byteArrayId);
        if (offset < 0) return null;

        int length = primData.readIntAt(offset);
        if (length <= 0) return length == 0 ? "" : null;

        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = primData.readByteAt(offset + 4 + i);

        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        return s.length() > 50 ? s.substring(0, 20) + "..." + s.substring(s.length() - 20) : s;
    }

    private static boolean containsGlob(String s) {
        return s.indexOf('*') >= 0;
    }

    private static boolean matchGlob(String s, String pattern) {
        String[] chunks = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < chunks.length; i++) {
            int idx = s.indexOf(chunks[i], pos);
            if (idx < 0) return false;
            if (i == 0 && !chunks[0].isEmpty() && idx != 0) return false; // prefix
            pos = idx + chunks[i].length();
        }
        return chunks[chunks.length - 1].isEmpty() || pos == s.length(); // suffix
    }
}
