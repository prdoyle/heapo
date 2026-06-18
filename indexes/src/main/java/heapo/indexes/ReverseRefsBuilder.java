package heapo.indexes;

import heapo.util.ExternalMergeSort;
import heapo.unpack.UnpackedHeap;

import java.io.*;
import java.nio.file.*;

/**
 * Builds reverse-refs CSR by transposing the forward-refs CSR.
 * For each edge (src, dst) in forward-refs, emits (dst, src).
 */
public final class ReverseRefsBuilder {

    private ReverseRefsBuilder() {}

    public static void build(UnpackedHeap heap, Path tempDir) throws IOException {
        int  objectCount = heap.objectCount();
        Path indexDir    = heap.indexDir();

        Path scratchPairs = tempDir.resolve("rr-pairs.bin");
        Path sortedPairs  = tempDir.resolve("rr-pairs-sorted.bin");

        // Emit (dst, src) pairs from forward-refs
        try (var fwdRefs = new CsrReader(
                    indexDir.resolve("forward-refs-offsets.bin"),
                    indexDir.resolve("forward-refs-edges.bin"));
             var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(scratchPairs), 1 << 16))) {
            for (int src = 0; src < objectCount; src++) {
                for (long e = fwdRefs.start(src); e < fwdRefs.end(src); e++) {
                    int dst = fwdRefs.edge(e);
                    out.writeInt(dst);  // src of reverse edge
                    out.writeInt(src);  // dst of reverse edge
                }
            }
        }

        // Sort by (dst) which is the first int in each 8-byte record
        ExternalMergeSort.sort(scratchPairs, sortedPairs, tempDir, 8,
            (a, b) -> Integer.compareUnsigned(a.getInt(0), b.getInt(0)),
            ExternalMergeSort.DEFAULT_MAX_RAM_BYTES);
        Files.delete(scratchPairs);

        CsrBuilder.build(sortedPairs,
            indexDir.resolve("reverse-refs-offsets.bin"),
            indexDir.resolve("reverse-refs-edges.bin"),
            objectCount);
        Files.delete(sortedPairs);
    }
}
