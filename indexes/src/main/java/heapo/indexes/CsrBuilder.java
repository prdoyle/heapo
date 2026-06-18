package heapo.indexes;

import java.io.*;
import java.nio.file.*;

/**
 * Builds a CSR adjacency pair from a sorted (src: int, dst: int) pair file.
 * Each record is exactly 8 bytes. All src values must be in [0, vertexCount).
 */
public final class CsrBuilder {

    private CsrBuilder() {}

    /**
     * Write {@code offsetsOut} and {@code edgesOut} from {@code sortedPairsPath}.
     * The pairs file must be sorted by src (ascending). Uses two sequential passes.
     * Writes to temp files then atomically renames to avoid partial reads.
     */
    public static void build(Path sortedPairsPath, Path offsetsOut, Path edgesOut,
                             int vertexCount) throws IOException {
        Path tmpOffsets = offsetsOut.resolveSibling(offsetsOut.getFileName() + ".tmp");
        Path tmpEdges   = edgesOut.resolveSibling(edgesOut.getFileName() + ".tmp");
        try {
            buildInternal(sortedPairsPath, tmpOffsets, tmpEdges, vertexCount);
            Files.move(tmpOffsets, offsetsOut, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmpEdges,   edgesOut,   StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmpOffsets); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpEdges);   } catch (IOException ignored) {}
            throw e;
        }
    }

    private static void buildInternal(Path pairsPath, Path offsetsOut, Path edgesOut,
                                      int vertexCount) throws IOException {
        // Pass 1: count edges per src → build prefix-sum offsets
        long[] offsets = new long[vertexCount + 1];
        long totalEdges = 0;
        try (var in = dataIn(pairsPath)) {
            while (true) {
                try {
                    int src = in.readInt();
                    in.readInt();   // dst — counting only
                    offsets[src + 1]++;
                    totalEdges++;
                } catch (EOFException e) { break; }
            }
        }
        for (int i = 1; i <= vertexCount; i++) offsets[i] += offsets[i - 1];

        // Write offsets file
        try (var out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(offsetsOut), 1 << 16))) {
            for (long off : offsets) out.writeLong(off);
        }

        // Pass 2: write edges in src order
        try (var in  = dataIn(pairsPath);
             var out = new DataOutputStream(new BufferedOutputStream(
                         Files.newOutputStream(edgesOut), 1 << 16))) {
            while (true) {
                try {
                    in.readInt();                // src — skip
                    out.writeInt(in.readInt());  // dst
                } catch (EOFException e) { break; }
            }
        }
    }

    private static DataInputStream dataIn(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16));
    }
}
