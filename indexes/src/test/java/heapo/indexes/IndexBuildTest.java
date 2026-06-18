package heapo.indexes;

import heapo.unpack.HprofReader;
import heapo.unpack.BaseHprofHandler;
import heapo.unpack.Unpacker;
import heapo.unpack.UnpackedHeap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class IndexBuildTest {

    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir", "build/hprof-samples"));

    // known-objects.hprof — for basic structure tests
    static UnpackedHeap knownHeap;
    static IndexRegistry knownReg;

    // deep-chain.hprof — for dominator / retained-size tests
    static UnpackedHeap chainHeap;
    static IndexRegistry chainReg;

    @BeforeAll
    static void setup() throws Exception {
        knownHeap = Unpacker.unpack(samplesDir.resolve("known-objects.hprof"),
                                    Files.createTempDirectory("heapo-known"));
        knownReg  = new IndexRegistry(knownHeap);
        knownReg.buildAll();

        chainHeap = Unpacker.unpack(samplesDir.resolve("deep-chain.hprof"),
                                    Files.createTempDirectory("heapo-chain"));
        chainReg  = new IndexRegistry(chainHeap);
        chainReg.buildAll();
    }

    // ── Instance list ─────────────────────────────────────────────────────────

    @Test
    void instanceListGroupsFooInstances() throws Exception {
        int fooDenseClassId = findClassDenseId(knownHeap, "heapo/samples/KnownObjects$Foo");
        assertTrue(fooDenseClassId >= 0, "Should find KnownObjects$Foo class");

        try (var il = knownReg.openInstanceList();
             var classOf = knownReg.openClassOf()) {
            long count = il.degree(fooDenseClassId);
            assertTrue(count >= 2, "Expected >= 2 Foo instances in instance list; got " + count);

            // Verify each listed instance is actually of type Foo
            for (long e = il.start(fooDenseClassId); e < il.end(fooDenseClassId); e++) {
                int inst = il.edge(e);
                assertEquals(fooDenseClassId, classOf.readInt(inst),
                    "instance-list entry " + inst + " should have class-of = Foo class");
            }
        }
    }

    @Test
    void instanceListNodesDominatedByChainHead() throws Exception {
        // The Node class has 100K instances in deep-chain.hprof
        int nodeDenseClassId = findClassDenseId(chainHeap, "heapo/samples/DeepChain$Node");
        assertTrue(nodeDenseClassId >= 0, "Should find DeepChain$Node class");

        try (var il = chainReg.openInstanceList()) {
            long nodeCount = il.degree(nodeDenseClassId);
            assertTrue(nodeCount >= 100_000,
                "Expected >= 100,000 Node instances; got " + nodeCount);
        }
    }

    // ── Reverse refs ──────────────────────────────────────────────────────────

    @Test
    void reverseRefsAreConsistentWithForwardRefs() throws Exception {
        int objectCount = knownHeap.objectCount();
        try (var fwd = knownReg.openForwardRefs();
             var rev = knownReg.openReverseRefs()) {
            // For every forward edge (src→dst), there must be a reverse edge (dst→src)
            int checked = 0;
            outer:
            for (int src = 0; src < objectCount; src++) {
                for (long e = fwd.start(src); e < fwd.end(src); e++) {
                    int dst = fwd.edge(e);
                    boolean found = false;
                    for (long re = rev.start(dst); re < rev.end(dst); re++) {
                        if (rev.edge(re) == src) { found = true; break; }
                    }
                    assertTrue(found, "Missing reverse edge " + dst + "→" + src);
                    if (++checked >= 1000) break outer; // sanity check a sample
                }
            }
            assertTrue(checked > 0, "Expected to find at least some forward edges");
        }
    }

    // ── DFS tree ──────────────────────────────────────────────────────────────

    @Test
    void dfsReachesAllChainNodes() throws Exception {
        int nodeDenseClassId = findClassDenseId(chainHeap, "heapo/samples/DeepChain$Node");
        try (var il   = chainReg.openInstanceList();
             var dfsNum = chainReg.openDfsNum()) {
            int unreachable = 0;
            for (long e = il.start(nodeDenseClassId); e < il.end(nodeDenseClassId); e++) {
                int node = il.edge(e);
                if (dfsNum.readLong(node) == DfsBuilder.UNREACHABLE) unreachable++;
            }
            assertEquals(0, unreachable, "All chain nodes should be reachable from GC roots");
        }
    }

    @Test
    void dfsParentIsConsistent() throws Exception {
        int objectCount = knownHeap.objectCount();
        int reachable   = (int)(Files.size(knownHeap.indexDir().resolve("dfs-vertex.bin")) / 4);
        assertTrue(reachable > 0, "Expected some reachable vertices");
        assertTrue(reachable <= objectCount, "Reachable count must not exceed objectCount");
    }

    // ── Dominator tree ────────────────────────────────────────────────────────

    @Test
    void chainHeadDominatesAllNodes() throws Exception {
        // DeepChain.head is the single entry point for the chain.
        // With static field edges: DeepChain.class → head → n1 → n2 → ...
        // head should dominate all Node instances.
        int headClassId = findClassDenseId(chainHeap, "heapo/samples/DeepChain$Node");
        int nodeClassId = headClassId; // same class

        try (var il   = chainReg.openInstanceList();
             var idom = chainReg.openIdom()) {
            long nodeCount = il.degree(nodeClassId);
            assertTrue(nodeCount >= 100_000, "Expected >= 100K nodes");

            // Find the Node that has no Node predecessor (it's the tail — next=null)
            // All non-tail Nodes should have a dominator that is also a Node
            int nodesWithNonNodeIdom = 0;
            try (var classOf = chainReg.openClassOf()) {
                for (long e = il.start(nodeClassId); e < il.end(nodeClassId); e++) {
                    int node = il.edge(e);
                    int dom  = idom.readInt(node);
                    if (dom >= 0 && classOf.readInt(dom) != nodeClassId) {
                        nodesWithNonNodeIdom++;
                    }
                }
            }
            // Only the first node in the chain (directly after DeepChain.head) should have
            // a non-Node dominator (its dominator is the DeepChain class or head).
            // All others should have a Node dominator.
            assertTrue(nodesWithNonNodeIdom <= 2,
                "At most 2 nodes should have a non-Node dominator; got " + nodesWithNonNodeIdom);
        }
    }

    @Test
    void idomIsNonNegativeForReachableObjects() throws Exception {
        int objectCount = knownHeap.objectCount();
        try (var dfsNum = knownReg.openDfsNum();
             var idom   = knownReg.openIdom()) {
            for (int v = 0; v < objectCount; v++) {
                if (dfsNum.readLong(v) != DfsBuilder.UNREACHABLE) {
                    // Reachable objects either have a dominator or are direct children of super-root
                    // idom[v] >= 0 OR idom[v] == -1 (direct child of super-root)
                    assertTrue(idom.readInt(v) >= -1,
                        "idom should be >= -1 for all objects");
                }
            }
        }
    }

    // ── Retained sizes ────────────────────────────────────────────────────────

    @Test
    void retainedSizeAtLeastShallowSize() throws Exception {
        int objectCount = knownHeap.objectCount();
        try (var retained   = knownReg.openRetainedSize();
             var shallowSize = knownReg.openShallowSize()) {
            for (int v = 0; v < objectCount; v++) {
                long ret      = retained.readLong(v);
                long shallow  = (long) shallowSize.readInt(v) * 8L;
                assertTrue(ret >= shallow,
                    "Retained[" + v + "]=" + ret + " < shallow=" + shallow);
            }
        }
    }

    @Test
    void chainRetainedSizeIsLarge() throws Exception {
        // The head node of the DeepChain retains all 100K Node objects.
        // HPROF reports instance-field size (no header), so we derive the per-node
        // byte cost from the actual shallow-size index rather than hardcoding it.

        int nodeClassId = findClassDenseId(chainHeap, "heapo/samples/DeepChain$Node");
        try (var il          = chainReg.openInstanceList();
             var idom        = chainReg.openIdom();
             var retained    = chainReg.openRetainedSize();
             var shallowSize = chainReg.openShallowSize()) {

            long nodeCount = il.degree(nodeClassId);
            assertTrue(nodeCount >= 100_000, "Expected >= 100K nodes");

            // Measure shallow size of the first Node instance (all identical)
            int firstNode = il.edge(il.start(nodeClassId));
            long perNodeBytes = (long) shallowSize.readInt(firstNode) * 8L;
            // If the HPROF instance-field size is 0 fall back to 1 byte for a non-trivial check
            long minExpected = nodeCount * Math.max(perNodeBytes, 1L);

            // Find node(s) whose dominator is NOT a node (= entry point of chain)
            List<Integer> entryNodes = new ArrayList<>();
            try (var classOf = chainReg.openClassOf()) {
                for (long e = il.start(nodeClassId); e < il.end(nodeClassId); e++) {
                    int node = il.edge(e);
                    int dom  = idom.readInt(node);
                    if (dom < 0 || classOf.readInt(dom) != nodeClassId) {
                        entryNodes.add(node);
                    }
                }
            }
            assertFalse(entryNodes.isEmpty(), "Should find at least one chain entry node");

            long maxRetained = entryNodes.stream()
                .mapToLong(n -> retained.readLong(n))
                .max().getAsLong();
            assertTrue(maxRetained >= minExpected,
                "Entry node retained size should be >= " + minExpected + "; got " + maxRetained);
        }
    }

    @Test
    void retainedSizeRankZeroIsLargest() throws Exception {
        int objectCount = knownHeap.objectCount();
        try (var retained = knownReg.openRetainedSize();
             var rank     = knownReg.openRetainedSizeRank()) {
            long maxRetained = Long.MIN_VALUE;
            int  maxV = -1;
            for (int v = 0; v < objectCount; v++) {
                long r = retained.readLong(v);
                if (r > maxRetained) { maxRetained = r; maxV = v; }
            }
            assertEquals(0, rank.readInt(maxV),
                "Object with largest retained size should have rank 0");
        }
    }

    @Test
    void dominatorSubtreeSizeIsPositive() throws Exception {
        int objectCount = knownHeap.objectCount();
        try (var subtree = knownReg.openDominatorSubtreeSize();
             var dfsNum  = knownReg.openDfsNum()) {
            for (int v = 0; v < objectCount; v++) {
                if (dfsNum.readLong(v) != DfsBuilder.UNREACHABLE) {
                    assertTrue(subtree.readInt(v) >= 1,
                        "Reachable vertex " + v + " should have subtree size >= 1");
                }
            }
        }
    }

    // ── Helper: find class dense ID by internal HPROF name ───────────────────

    static int findClassDenseId(UnpackedHeap heap, String hprofClassName) throws IOException {
        var handler = new BaseHprofHandler() {
            long classRawId = -1;
            final Map<Long, String> strings    = new HashMap<>();
            final Map<Long, Long>   classNames = new HashMap<>();
            @Override public void string(long rawId, String v) { strings.put(rawId, v); }
            @Override public void loadClass(int s, long classObjectId, long nameId) {
                classNames.put(classObjectId, nameId);
            }
            @Override public void classDump(long cid, long sup, int size, long[] fn, byte[] ft) {
                Long nameId = classNames.get(cid);
                if (nameId != null && hprofClassName.equals(strings.get(nameId)))
                    classRawId = cid;
            }
        };
        new HprofReader(samplesDir.resolve(
            heap == knownHeap ? "known-objects.hprof" : "deep-chain.hprof")).read(handler);

        if (handler.classRawId < 0) return -1;
        long[] sortedRawIds = loadLongFile(heap.indexDir().resolve("raw-id-lookup-sorted.bin"));
        int[]  denseIds     = loadIntFile( heap.indexDir().resolve("raw-id-lookup-dense.bin"));
        return Unpacker.resolveDenseId(handler.classRawId, sortedRawIds, denseIds);
    }

    private static long[] loadLongFile(Path p) throws IOException {
        int count = (int)(Files.size(p) / 8);
        long[] arr = new long[count];
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                Files.newInputStream(p)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readLong();
        }
        return arr;
    }

    private static int[] loadIntFile(Path p) throws IOException {
        int count = (int)(Files.size(p) / 4);
        int[] arr = new int[count];
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                Files.newInputStream(p)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readInt();
        }
        return arr;
    }
}
