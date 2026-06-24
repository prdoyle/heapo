package heapo.indexes;

import heapo.util.ExternalMergeSort;
import heapo.util.IndexFile;
import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;

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

        int reachable = (int)(Files.size(indexDir.resolve("dfs-vertex.bin")) / 4);

        // Temp mmap'd files
        Path tmpChildCount   = tempDir.resolve("rs-childCount.bin.tmp");
        Path tmpChildOffsets = tempDir.resolve("rs-childOffsets.bin.tmp");
        Path tmpCursor       = tempDir.resolve("rs-cursor.bin.tmp");
        Path tmpChildEdges   = tempDir.resolve("rs-childEdges.bin.tmp");
        Path tmpRetained     = tempDir.resolve("rs-retained.bin.tmp");
        Path tmpRank         = tempDir.resolve("rs-rank.bin.tmp");
        Path tmpSubtree      = tempDir.resolve("rs-subtree.bin.tmp");

        try (var idom        = IndexFile.openRead(indexDir.resolve("idom.bin"));
             var shallowSize = IndexFile.openRead(indexDir.resolve("shallow-size.bin"));
             var dfsVertex   = IndexFile.openRead(indexDir.resolve("dfs-vertex.bin"))) {

            // ── 1. Build dominator-children CSR ──────────────────────────────

            // Count children per vertex
            try (var childCount = IndexFile.create(tmpChildCount, objectCount, 4)) {
                for (int v = 0; v < objectCount; v++) {
                    int idomV = idom.readInt(v);
                    if (idomV >= 0) childCount.writeInt(idomV, childCount.readInt(idomV) + 1);
                }

                // Prefix-sum offsets
                try (var childOffsets = IndexFile.create(tmpChildOffsets, (long) objectCount + 1, 8)) {
                    for (int i = 0; i < objectCount; i++)
                        childOffsets.writeLong(i + 1, childOffsets.readLong(i) + childCount.readInt(i));

                    // Scatter children
                    long edgeCount = childOffsets.readLong(objectCount);
                    try (var cursor     = IndexFile.create(tmpCursor, objectCount, 4);
                         var childEdges = IndexFile.create(tmpChildEdges, edgeCount, 4)) {
                        for (int i = 0; i < objectCount; i++)
                            cursor.writeInt(i, (int) childOffsets.readLong(i));
                        for (int v = 0; v < objectCount; v++) {
                            int idomV = idom.readInt(v);
                            if (idomV >= 0) {
                                int pos = cursor.readInt(idomV);
                                childEdges.writeInt(pos, v);
                                cursor.writeInt(idomV, pos + 1);
                            }
                        }
                    }
                    Files.deleteIfExists(tmpCursor);

                    // Write dominator-children CSR
                    writeThenMove(indexDir.resolve("dominator-children-offsets.bin"),
                                  tempDir.resolve("dc-offsets.tmp"), out -> {
                        for (long i = 0; i <= objectCount; i++) out.writeLong(childOffsets.readLong(i));
                    });
                }
            }
            Files.deleteIfExists(tmpChildCount);
            writeThenMove(indexDir.resolve("dominator-children-edges.bin"),
                          tempDir.resolve("dc-edges.tmp"), out -> {
                try (var childEdges = IndexFile.openRead(tmpChildEdges)) {
                    for (long i = 0; i < childEdges.intCount(); i++) out.writeInt(childEdges.readInt(i));
                }
            });
            Files.deleteIfExists(tmpChildEdges);

            // ── 2. Compute retained sizes (bottom-up in reverse DFS order) ───

            try (var retained = IndexFile.create(tmpRetained, objectCount, 8)) {
                for (int v = 0; v < objectCount; v++)
                    retained.writeLong(v, (long) shallowSize.readInt(v) * 8L);

                for (int i = reachable - 1; i >= 0; i--) {
                    int v     = dfsVertex.readInt(i);
                    int idomV = idom.readInt(v);
                    if (idomV >= 0) retained.writeLong(idomV, retained.readLong(idomV) + retained.readLong(v));
                }

                writeThenMove(indexDir.resolve("retained-size.bin"),
                              tempDir.resolve("retained-final.tmp"), out -> {
                    for (int v = 0; v < objectCount; v++) out.writeLong(retained.readLong(v));
                });

                // ── 3. Retained-size rank (external sort) ──────────────────────

                Path rankScratch = tempDir.resolve("rank-scratch.bin");
                Path rankSorted  = tempDir.resolve("rank-sorted.bin");

                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(rankScratch)))) {
                    for (int v = 0; v < objectCount; v++) {
                        out.writeLong(retained.readLong(v));
                        out.writeInt(v);
                    }
                }
                ExternalMergeSort.sort(rankScratch, rankSorted, tempDir, 12,
                    (a, b) -> Long.compare(b.getLong(0), a.getLong(0)),
                    ExternalMergeSort.DEFAULT_MAX_RAM_BYTES);
                Files.delete(rankScratch);

                try (var rank = IndexFile.create(tmpRank, objectCount, 4)) {
                    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(rankSorted)))) {
                        for (int pos = 0; pos < objectCount; pos++) {
                            in.readLong();
                            rank.writeInt(in.readInt(), pos);
                        }
                    }
                    Files.delete(rankSorted);

                    writeThenMove(indexDir.resolve("retained-size-rank.bin"),
                                  tempDir.resolve("rank.tmp"), out -> {
                        for (int v = 0; v < objectCount; v++) out.writeInt(rank.readInt(v));
                    });
                }
                Files.deleteIfExists(tmpRank);
            }
            Files.deleteIfExists(tmpRetained);

            // ── 4. Dominator subtree size ───────────────────────────────────────

            try (var subtreeSize = IndexFile.create(tmpSubtree, objectCount, 4)) {
                for (int v = 0; v < objectCount; v++) subtreeSize.writeInt(v, 1);
                for (int i = reachable - 1; i >= 0; i--) {
                    int v     = dfsVertex.readInt(i);
                    int idomV = idom.readInt(v);
                    if (idomV >= 0)
                        subtreeSize.writeInt(idomV, subtreeSize.readInt(idomV) + subtreeSize.readInt(v));
                }

                writeThenMove(indexDir.resolve("dominator-subtree-size.bin"),
                              tempDir.resolve("subtree.tmp"), out -> {
                    for (int v = 0; v < objectCount; v++) out.writeInt(subtreeSize.readInt(v));
                });
            }
            Files.deleteIfExists(tmpSubtree);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface interface DataWriter { void write(DataOutputStream out) throws IOException; }

    private static void writeThenMove(Path dest, Path tmp, DataWriter w) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            w.write(out);
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
