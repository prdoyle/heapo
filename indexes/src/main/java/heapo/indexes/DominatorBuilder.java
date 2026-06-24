package heapo.indexes;

import heapo.util.IndexFile;
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

        // n = total vertices in DFS tree (super-root at pos 0; real vertices at 1..reachableCount)
        int reachableCount = (int)(Files.size(indexDir.resolve("dfs-vertex.bin")) / 4);
        int n = reachableCount + 1;

        // LT temp arrays (indexed by DFS position, size n) — mmap'd to avoid heap pressure
        Path tmpSemi       = tempDir.resolve("lt-semi.bin.tmp");
        Path tmpIdomDfs    = tempDir.resolve("lt-idomDfs.bin.tmp");
        Path tmpAncestor   = tempDir.resolve("lt-ancestor.bin.tmp");
        Path tmpLabel      = tempDir.resolve("lt-label.bin.tmp");
        Path tmpBucketHead = tempDir.resolve("lt-bucketHead.bin.tmp");
        Path tmpBucketNext = tempDir.resolve("lt-bucketNext.bin.tmp");
        Path tmpIdom       = tempDir.resolve("idom.bin.tmp");

        try (var dfsVertex = IndexFile.openRead(indexDir.resolve("dfs-vertex.bin"));
             var dfsNumRaw = IndexFile.openRead(indexDir.resolve("dfs-num.bin"));
             var dfsParent = IndexFile.openRead(indexDir.resolve("dfs-parent.bin"));
             var semi       = IndexFile.create(tmpSemi,       n, 4);
             var idomDfs    = IndexFile.create(tmpIdomDfs,    n, 4);
             var ancestor   = IndexFile.create(tmpAncestor,   n, 4);
             var label      = IndexFile.create(tmpLabel,      n, 4);
             var bucketHead = IndexFile.create(tmpBucketHead, n, 4);
             var bucketNext = IndexFile.create(tmpBucketNext, n, 4);
             var idom       = IndexFile.create(tmpIdom, objectCount, 4)) {

            // Initialize LT arrays
            for (int i = 0; i < n; i++) {
                semi.writeInt(i, i);
                label.writeInt(i, i);
                ancestor.writeInt(i, -1);
                bucketHead.writeInt(i, -1);
                bucketNext.writeInt(i, -1);
            }
            for (int i = 0; i < objectCount; i++) idom.writeInt(i, -1);

            // Scratch stack for iterative EVAL (O(depth), not O(N))
            int[] evalStack = new int[64];

            // ── Step 2: compute semidominators (reverse DFS order) ────────────

            try (var revRefs = new CsrReader(
                    indexDir.resolve("reverse-refs-offsets.bin"),
                    indexDir.resolve("reverse-refs-edges.bin"))) {

                for (int w = n - 1; w >= 1; w--) {
                    int wVertex = dfsVertex.readInt(w - 1); // dense ID at DFS pos w

                    for (long e = revRefs.start(wVertex); e < revRefs.end(wVertex); e++) {
                        int  uVertex  = revRefs.edge(e);
                        long uPosLong = dfsNumRaw.readLong(uVertex);
                        if (uPosLong == DfsBuilder.UNREACHABLE) continue;
                        int u     = (int) uPosLong + 1; // +1: super-root at pos 0
                        int evalU = eval(u, ancestor, label, semi, evalStack);
                        if (semi.readInt(evalU) < semi.readInt(w)) semi.writeInt(w, semi.readInt(evalU));
                    }

                    // Add w to bucket of its semidominator
                    int semiW = semi.readInt(w);
                    bucketNext.writeInt(w, bucketHead.readInt(semiW));
                    bucketHead.writeInt(semiW, w);

                    // LINK(parent[w], w)
                    int pw = dfsParentPos(wVertex, dfsParent, dfsNumRaw);
                    link(pw, w, ancestor);

                    // Process bucket of parent[w]
                    int v = bucketHead.readInt(pw);
                    while (v != -1) {
                        int next  = bucketNext.readInt(v);
                        int evalV = eval(v, ancestor, label, semi, evalStack);
                        idomDfs.writeInt(v, (semi.readInt(evalV) < semi.readInt(v)) ? evalV : pw);
                        v = next;
                    }
                    bucketHead.writeInt(pw, -1);
                }
            }

            // ── Step 3: finalize dominators ───────────────────────────────────

            for (int w = 1; w < n; w++) {
                if (idomDfs.readInt(w) != semi.readInt(w)) {
                    idomDfs.writeInt(w, idomDfs.readInt(idomDfs.readInt(w)));
                }
            }

            // Convert DFS-indexed idomDfs → vertex-indexed idom (dense IDs)
            for (int w = 1; w < n; w++) {
                int v       = dfsVertex.readInt(w - 1);
                int idomPos = idomDfs.readInt(w);
                idom.writeInt(v, idomPos <= 0 ? -1 : dfsVertex.readInt(idomPos - 1));
            }
        }

        // Delete LT temp files (kept separate from idom which is the final output)
        for (Path p : new Path[]{tmpSemi, tmpIdomDfs, tmpAncestor, tmpLabel, tmpBucketHead, tmpBucketNext}) {
            Files.deleteIfExists(p);
        }
        Files.move(tmpIdom, indexDir.resolve("idom.bin"),
                   StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── LINK: simple forest linking ───────────────────────────────────────────

    private static void link(int v, int w, IndexFile ancestor) {
        ancestor.writeInt(w, v);
    }

    // ── EVAL: iterative path compression ─────────────────────────────────────

    private static int eval(int v, IndexFile ancestor, IndexFile label, IndexFile semi, int[] stack) {
        if (ancestor.readInt(v) == -1) return label.readInt(v);

        // Collect path from v to root (where ancestor == -1)
        int len = 0;
        int w = v;
        while (ancestor.readInt(w) != -1) {
            if (len >= stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
            stack[len++] = w;
            w = ancestor.readInt(w);
        }
        // w is the root of this tree

        // Walk back toward v, compressing and propagating minimum label
        for (int i = len - 1; i >= 0; i--) {
            int x   = stack[i];
            int anc = ancestor.readInt(x);
            if (semi.readInt(label.readInt(anc)) < semi.readInt(label.readInt(x)))
                label.writeInt(x, label.readInt(anc));
            ancestor.writeInt(x, w);  // path compression to root
        }
        return label.readInt(v);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** DFS position of the DFS-tree parent of vertex v; 0 if parent is super-root or v is root. */
    private static int dfsParentPos(int v, IndexFile dfsParent, IndexFile dfsNumRaw) {
        int p = dfsParent.readInt(v);
        if (p < 0) return 0;  // parent is super-root (DFS pos 0)
        long pPos = dfsNumRaw.readLong(p);
        return (pPos == DfsBuilder.UNREACHABLE) ? 0 : (int) pPos + 1;
    }
}
