package heapo.indexes;

import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Computes the dominator tree using the Lengauer-Tarjan algorithm.
 *
 * All arrays are indexed by DFS number (0 = super-root, 1..n = real vertices in DFS order).
 * The super-root (dense ID = objectCount) is treated as vertex 0 in DFS numbering.
 *
 * Produces idom.bin: int[objectCount] where idom[v] = immediate dominator dense ID of v,
 * or -1 if v is unreachable or is a direct child of the super-root.
 */
public final class DominatorBuilder {

    private DominatorBuilder() {}

    public static void build(UnpackedHeap heap, Path tempDir) throws IOException {
        int  objectCount = heap.objectCount();
        Path indexDir    = heap.indexDir();

        // Load DFS data
        // dfsVertex[n] = dense ID of vertex at DFS position n (0-indexed, excluding super-root)
        int reachableCount = (int)(Files.size(indexDir.resolve("dfs-vertex.bin")) / 4);
        int[] dfsVertex = loadInts(indexDir.resolve("dfs-vertex.bin"), reachableCount);

        // dfsNum[v] = DFS position of vertex v (UNREACHABLE if not visited)
        long[] dfsNumRaw = loadLongs(indexDir.resolve("dfs-num.bin"), objectCount);

        // dfsParent[v] = parent dense ID in DFS tree (-1 = root/unreachable)
        int[] dfsParent = loadInts(indexDir.resolve("dfs-parent.bin"), objectCount);

        // n = total vertices in DFS tree (including super-root at position 0)
        // Super-root occupies position 0; real vertices at positions 1..reachableCount
        int n = reachableCount + 1;

        // LT arrays indexed by DFS position (0 = super-root)
        int[] semi     = new int[n];   // semidominator DFS position
        int[] idomDfs  = new int[n];   // immediate dominator DFS position (final)
        int[] ancestor = new int[n];   // forest ancestor for EVAL/LINK
        int[] label    = new int[n];   // label for path compression
        // Bucket: for each DFS position p, bucket[p] = list of DFS positions whose
        // semidominator is p. We use a simple linked-list with next[] array.
        int[] bucketHead = new int[n];
        int[] bucketNext = new int[n];

        Arrays.fill(bucketHead, -1);
        Arrays.fill(bucketNext, -1);
        Arrays.fill(ancestor, -1);

        // Initialize: semi[i] = i, label[i] = i
        for (int i = 0; i < n; i++) { semi[i] = i; label[i] = i; }

        // Scratch stack for iterative EVAL
        int[] evalStack = new int[64];

        // ── Step 2: compute semidominators (process in reverse DFS order) ──────

        try (var revRefs = new CsrReader(
                indexDir.resolve("reverse-refs-offsets.bin"),
                indexDir.resolve("reverse-refs-edges.bin"))) {

            // Process DFS positions n-1 down to 1 (skip 0 = super-root)
            for (int w = n - 1; w >= 1; w--) {
                int wVertex = dfsVertex[w - 1];  // actual dense ID (DFS pos w = vertex index w-1)

                // For each predecessor u of wVertex in the original graph
                for (long e = revRefs.start(wVertex); e < revRefs.end(wVertex); e++) {
                    int uVertex = revRefs.edge(e);
                    // DFS position of uVertex
                    long uPosLong = dfsNumRaw[uVertex];
                    if (uPosLong == DfsBuilder.UNREACHABLE) continue;
                    int u = (int) uPosLong + 1; // +1 because super-root is at pos 0
                    int evalU = eval(u, ancestor, label, semi, evalStack);
                    if (semi[evalU] < semi[w]) semi[w] = semi[evalU];
                }
                // Also process super-root as predecessor if wVertex is a GC root
                // (handled implicitly: if parent of wVertex is -1, its parent in DFS is super-root = 0)

                // Add w to bucket of its semidominator
                bucketNext[w] = bucketHead[semi[w]];
                bucketHead[semi[w]] = w;

                // LINK(parent[w], w)
                int pw = dfsParentPos(wVertex, dfsParent, dfsNumRaw); // DFS pos of parent
                link(pw, w, ancestor);

                // Process bucket of parent[w]
                int v = bucketHead[pw];
                while (v != -1) {
                    int next = bucketNext[v];
                    int evalV = eval(v, ancestor, label, semi, evalStack);
                    idomDfs[v] = (semi[evalV] < semi[v]) ? evalV : pw;
                    v = next;
                }
                bucketHead[pw] = -1;
            }
        }

        // ── Step 3: finalize dominators ───────────────────────────────────────

        for (int w = 1; w < n; w++) {
            if (idomDfs[w] != semi[w]) {
                idomDfs[w] = idomDfs[idomDfs[w]];
            }
        }

        // Convert from DFS-indexed idomDfs to vertex-indexed idom[] (dense IDs)
        // idom[v] = dense ID of idom, or -1 if v unreachable or direct child of super-root
        int[] idom = new int[objectCount];
        Arrays.fill(idom, -1);
        for (int w = 1; w < n; w++) {
            int v = dfsVertex[w - 1]; // dense ID of vertex at DFS pos w
            int idomPos = idomDfs[w];
            if (idomPos <= 0) {
                idom[v] = -1; // dominated by super-root → no real dominator
            } else {
                idom[v] = dfsVertex[idomPos - 1];
            }
        }

        // Write idom.bin
        Path tmp = tempDir.resolve("idom.bin.tmp");
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (int id : idom) out.writeInt(id);
        }
        Files.move(tmp, indexDir.resolve("idom.bin"), StandardCopyOption.ATOMIC_MOVE,
                   StandardCopyOption.REPLACE_EXISTING);
    }

    // ── LINK: simple forest linking ───────────────────────────────────────────

    private static void link(int v, int w, int[] ancestor) {
        ancestor[w] = v;
    }

    // ── EVAL: iterative path compression ─────────────────────────────────────

    private static int eval(int v, int[] ancestor, int[] label, int[] semi, int[] stack) {
        if (ancestor[v] == -1) return label[v];

        // Collect path from v to root (where ancestor == -1)
        int len = 0;
        int w = v;
        while (ancestor[w] != -1) {
            if (len >= stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
            stack[len++] = w;
            w = ancestor[w];
        }
        // w is the root of this tree

        // Walk back toward v, compressing and propagating minimum label
        for (int i = len - 1; i >= 0; i--) {
            int x = stack[i];
            int anc = ancestor[x];
            if (semi[label[anc]] < semi[label[x]]) label[x] = label[anc];
            ancestor[x] = w;  // path compression to root
        }
        return label[v];
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** DFS position of the DFS-tree parent of vertex v; 0 if parent is super-root or v is root. */
    private static int dfsParentPos(int v, int[] dfsParent, long[] dfsNumRaw) {
        int p = dfsParent[v];
        if (p < 0) return 0;  // parent is super-root (DFS pos 0)
        long pPos = dfsNumRaw[p];
        return (pPos == DfsBuilder.UNREACHABLE) ? 0 : (int) pPos + 1;
    }

    private static int[] loadInts(Path path, int count) throws IOException {
        int[] arr = new int[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readInt();
        }
        return arr;
    }

    private static long[] loadLongs(Path path, int count) throws IOException {
        long[] arr = new long[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readLong();
        }
        return arr;
    }
}
