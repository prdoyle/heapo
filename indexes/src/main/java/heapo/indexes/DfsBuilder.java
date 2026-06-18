package heapo.indexes;

import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;
import java.util.BitSet;

/**
 * Iterative DFS from a synthetic super-root (dense ID = objectCount).
 * The super-root has synthetic edges to all GC roots.
 *
 * Produces:
 *   dfs-num.bin     — long[]  dfsNum[v] = discovery number (-1 if unreachable)
 *   dfs-vertex.bin  — int[]   dfsVertex[n] = vertex at discovery position n
 *   dfs-parent.bin  — int[]   parent[v] = parent in DFS tree (-1 for root/unreachable)
 */
public final class DfsBuilder {

    /** Sentinel: unreachable vertex. */
    public static final long UNREACHABLE = -1L;

    private DfsBuilder() {}

    public static void build(UnpackedHeap heap, Path tempDir) throws IOException {
        int  objectCount = heap.objectCount();
        Path indexDir    = heap.indexDir();

        // super-root = objectCount (synthetic; not stored in any file)
        int superRoot = objectCount;
        int totalNodes = objectCount + 1; // real objects + super-root

        // Load gc-roots
        int[] gcRoots = loadGcRoots(indexDir.resolve("gc-roots.bin"));

        // DFS result arrays (indexed by dense ID; super-root not stored in output files)
        long[] dfsNum   = new long[objectCount];
        int[]  dfsParent = new int[objectCount];
        int[]  dfsVertex = new int[objectCount];
        java.util.Arrays.fill(dfsNum,    UNREACHABLE);
        java.util.Arrays.fill(dfsParent, -1);

        BitSet visited = new BitSet(totalNodes);
        visited.set(superRoot);

        // Iterative DFS stack: parallel arrays of (vertex, next-edge-index)
        // For super-root, edges are the gc-roots; for others, use forward-refs CSR.
        // We encode: vertex >= 0 means real vertex, vertex == superRoot means super-root.
        int[] stackV    = new int[1024];
        long[] stackE   = new long[1024];  // next edge position in CSR (or index into gcRoots)
        int stackTop    = -1;
        long dfsCounter = 0;

        try (var fwdRefs = new CsrReader(
                indexDir.resolve("forward-refs-offsets.bin"),
                indexDir.resolve("forward-refs-edges.bin"))) {

            // Push super-root
            stackTop++;
            if (stackTop >= stackV.length) { stackV = grow(stackV); stackE = growL(stackE); }
            stackV[stackTop] = superRoot;
            stackE[stackTop] = 0;

            while (stackTop >= 0) {
                int v  = stackV[stackTop];
                long e = stackE[stackTop];

                int neighbor;
                long nextE;
                if (v == superRoot) {
                    // Edges of super-root are gc-roots array
                    if (e >= gcRoots.length) { stackTop--; continue; }
                    neighbor = gcRoots[(int) e];
                    nextE    = e + 1;
                } else {
                    long end = fwdRefs.end(v);
                    if (e >= end) { stackTop--; continue; }
                    neighbor = fwdRefs.edge(e);
                    nextE    = e + 1;
                }

                stackE[stackTop] = nextE;

                if (neighbor < 0 || neighbor >= objectCount || visited.get(neighbor)) continue;

                visited.set(neighbor);
                int parentV = (v == superRoot) ? -1 : v;
                dfsNum[neighbor]    = dfsCounter;
                dfsVertex[(int) dfsCounter] = neighbor;
                dfsParent[neighbor] = parentV;
                dfsCounter++;

                // Push neighbor
                stackTop++;
                if (stackTop >= stackV.length) { stackV = grow(stackV); stackE = growL(stackE); }
                stackV[stackTop] = neighbor;
                stackE[stackTop] = (neighbor < objectCount) ? fwdRefs.start(neighbor) : 0;
            }
        }

        // Write dfs-num.bin (long[] — UNREACHABLE for vertices not visited)
        writeThenMove(indexDir.resolve("dfs-num.bin"), tempDir.resolve("dfs-num.bin.tmp"), out -> {
            for (long n : dfsNum) out.writeLong(n);
        });

        // Write dfs-vertex.bin (int[] of length = reachable vertex count = dfsCounter)
        int reachableCount = (int) dfsCounter;
        writeThenMove(indexDir.resolve("dfs-vertex.bin"), tempDir.resolve("dfs-vertex.bin.tmp"), out -> {
            for (int i = 0; i < reachableCount; i++) out.writeInt(dfsVertex[i]);
        });

        // Write dfs-parent.bin (int[objectCount], -1 for unreachable/root)
        writeThenMove(indexDir.resolve("dfs-parent.bin"), tempDir.resolve("dfs-parent.bin.tmp"), out -> {
            for (int p : dfsParent) out.writeInt(p);
        });
    }

    private static int[] loadGcRoots(Path path) throws IOException {
        long size = Files.size(path);
        int count = (int) (size / 4);
        int[] roots = new int[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) roots[i] = in.readInt();
        }
        return roots;
    }

    @FunctionalInterface
    interface DataWriter { void write(DataOutputStream out) throws IOException; }

    private static void writeThenMove(Path dest, Path tmp, DataWriter w) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            w.write(out);
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int[]  grow(int[]  a) { return java.util.Arrays.copyOf(a, a.length * 2); }
    private static long[] growL(long[] a) { return java.util.Arrays.copyOf(a, a.length * 2); }
}
