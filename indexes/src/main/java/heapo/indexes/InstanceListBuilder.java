package heapo.indexes;

import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;

/**
 * Builds the instance-list CSR: for each class dense ID, the list of instances of that class.
 * Uses counting sort (class IDs are bounded by objectCount).
 */
public final class InstanceListBuilder {

    private InstanceListBuilder() {}

    public static void build(UnpackedHeap heap, Path tempDir) throws IOException {
        int     objectCount = heap.objectCount();
        Path    indexDir    = heap.indexDir();
        Path    classOfPath = indexDir.resolve("class-of.bin");

        // Counting sort: count instances per class
        int[] counts = new int[objectCount];
        try (var classOf = IndexFile.openRead(classOfPath)) {
            for (int i = 0; i < objectCount; i++) counts[classOf.readInt(i)]++;
        }

        // Compute prefix-sum offsets
        long[] offsets = new long[objectCount + 1];
        for (int i = 0; i < objectCount; i++) offsets[i + 1] = offsets[i] + counts[i];

        // Write sorted pairs (classId, instanceId) via sorted scratch
        // Re-use counts as write cursors
        int[] cursor = counts.clone();
        for (int i = 0; i < objectCount; i++) cursor[i] = (int) offsets[i];

        // Write edges in classId order: second pass over class-of
        Path    tmpEdges   = tempDir.resolve("il-edges.bin.tmp");
        Path    tmpOffsets = tempDir.resolve("il-offsets.bin.tmp");
        try {
            // Allocate edge array in memory (objectCount ints ≈ few MB for typical heaps)
            int[] edges = new int[objectCount];
            try (var classOf = IndexFile.openRead(classOfPath)) {
                for (int i = 0; i < objectCount; i++) {
                    int cls = classOf.readInt(i);
                    edges[cursor[cls]++] = i;
                }
            }

            // Write offsets
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmpOffsets)))) {
                for (long off : offsets) out.writeLong(off);
            }
            // Write edges
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmpEdges)))) {
                for (int e : edges) out.writeInt(e);
            }

            Path finalOffsets = indexDir.resolve("instance-list-offsets.bin");
            Path finalEdges   = indexDir.resolve("instance-list-edges.bin");
            Files.move(tmpOffsets, finalOffsets, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmpEdges,   finalEdges,   StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(tmpOffsets); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpEdges);   } catch (IOException ignored) {}
        }
    }
}
