package heapo.indexes;

import heapo.util.ExternalMergeSort;
import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Builds retained-size.bin, dominator-children CSR, retained-size-rank.bin,
 * and dominator-subtree-size.bin from idom.bin and shallow-size.bin.
 *
 * Walk the dominator tree bottom-up (reverse DFS order):
 *   retained[v] = shallowSize[v] + sum(retained[c] for c in domChildren[v])
 */
public final class RetainedSizeBuilder {

    private RetainedSizeBuilder() {}

    public static void build(UnpackedHeap heap, Path tempDir) throws IOException {
        int  objectCount = heap.objectCount();
        Path indexDir    = heap.indexDir();

        int[] idom        = loadInts( indexDir.resolve("idom.bin"),         objectCount);
        int[] shallowSize = loadInts( indexDir.resolve("shallow-size.bin"),  objectCount);
        int   reachable   = (int)(Files.size(indexDir.resolve("dfs-vertex.bin")) / 4);
        int[] dfsVertex   = loadInts( indexDir.resolve("dfs-vertex.bin"),    reachable);

        // ── 1. Build dominator-children CSR ──────────────────────────────────

        // Count children per vertex
        int[] childCount = new int[objectCount];
        for (int v = 0; v < objectCount; v++) {
            if (idom[v] >= 0) childCount[idom[v]]++;
        }
        // Prefix-sum offsets
        long[] childOffsets = new long[objectCount + 1];
        for (int i = 0; i < objectCount; i++) childOffsets[i + 1] = childOffsets[i] + childCount[i];

        // Fill children arrays
        int[] childEdges  = new int[(int) childOffsets[objectCount]];
        int[] cursor      = childCount.clone();  // re-use as write cursors
        for (int i = 0; i < objectCount; i++) cursor[i] = (int) childOffsets[i];
        for (int v = 0; v < objectCount; v++) {
            if (idom[v] >= 0) childEdges[cursor[idom[v]]++] = v;
        }

        writeThenMove(indexDir.resolve("dominator-children-offsets.bin"),
                      tempDir.resolve("dc-offsets.tmp"), out -> {
            for (long off : childOffsets) out.writeLong(off);
        });
        writeThenMove(indexDir.resolve("dominator-children-edges.bin"),
                      tempDir.resolve("dc-edges.tmp"), out -> {
            for (int e : childEdges) out.writeInt(e);
        });

        // ── 2. Compute retained sizes (bottom-up in reverse DFS order) ───────

        long[] retained = new long[objectCount];
        // Initialize with shallow sizes (convert from shifted value back to bytes)
        for (int v = 0; v < objectCount; v++) {
            retained[v] = (long) shallowSize[v] * 8L;
        }

        // Process in reverse DFS order: leaves before parents
        for (int i = reachable - 1; i >= 0; i--) {
            int v = dfsVertex[i];
            if (idom[v] >= 0) retained[idom[v]] += retained[v];
        }

        writeThenMove(indexDir.resolve("retained-size.bin"),
                      tempDir.resolve("retained.tmp"), out -> {
            for (long r : retained) out.writeLong(r);
        });

        // ── 3. Retained-size rank (external sort) ────────────────────────────

        Path rankScratch = tempDir.resolve("rank-scratch.bin");
        Path rankSorted  = tempDir.resolve("rank-sorted.bin");

        // Emit (retainedSize: long, denseId: int) = 12-byte records
        try (var out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(rankScratch)))) {
            for (int v = 0; v < objectCount; v++) {
                out.writeLong(retained[v]);
                out.writeInt(v);
            }
        }
        // Sort descending by retained size (negate for ascending sort)
        ExternalMergeSort.sort(rankScratch, rankSorted, tempDir, 12,
            (a, b) -> Long.compare(b.getLong(0), a.getLong(0)),  // descending
            ExternalMergeSort.DEFAULT_MAX_RAM_BYTES);
        Files.delete(rankScratch);

        // rank[v] = position in sorted order
        int[] rank = new int[objectCount];
        try (var in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(rankSorted)))) {
            for (int pos = 0; pos < objectCount; pos++) {
                in.readLong(); // retained size
                int v = in.readInt();
                rank[v] = pos;
            }
        }
        Files.delete(rankSorted);

        writeThenMove(indexDir.resolve("retained-size-rank.bin"),
                      tempDir.resolve("rank.tmp"), out -> {
            for (int r : rank) out.writeInt(r);
        });

        // ── 4. Dominator subtree size ─────────────────────────────────────────

        int[] subtreeSize = new int[objectCount];
        Arrays.fill(subtreeSize, 1); // each vertex counts itself
        for (int i = reachable - 1; i >= 0; i--) {
            int v = dfsVertex[i];
            if (idom[v] >= 0) subtreeSize[idom[v]] += subtreeSize[v];
        }

        writeThenMove(indexDir.resolve("dominator-subtree-size.bin"),
                      tempDir.resolve("subtree.tmp"), out -> {
            for (int s : subtreeSize) out.writeInt(s);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface interface DataWriter { void write(DataOutputStream out) throws IOException; }

    private static void writeThenMove(Path dest, Path tmp, DataWriter w) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            w.write(out);
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int[] loadInts(Path path, int count) throws IOException {
        int[] arr = new int[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readInt();
        }
        return arr;
    }
}
