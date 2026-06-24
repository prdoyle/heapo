package heapo.indexes;

import heapo.util.IndexFile;
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
        int  objectCount = heap.objectCount();
        Path indexDir    = heap.indexDir();
        Path classOfPath = indexDir.resolve("class-of.bin");

        Path tmpCounts  = tempDir.resolve("il-counts.bin.tmp");
        Path tmpOffsets = tempDir.resolve("il-offsets.bin.tmp");
        Path tmpCursor  = tempDir.resolve("il-cursor.bin.tmp");
        Path tmpEdges   = tempDir.resolve("il-edges.bin.tmp");
        try {
            // Counting sort pass: count instances per class
            try (var counts  = IndexFile.create(tmpCounts, objectCount, 4);
                 var classOf = IndexFile.openRead(classOfPath)) {
                for (int i = 0; i < objectCount; i++) {
                    int cls = classOf.readInt(i);
                    counts.writeInt(cls, counts.readInt(cls) + 1);
                }
            }

            // Prefix-sum: offsets[i+1] = offsets[i] + counts[i]
            try (var counts  = IndexFile.openRead(tmpCounts);
                 var offsets = IndexFile.create(tmpOffsets, (long) objectCount + 1, 8)) {
                for (int i = 0; i < objectCount; i++)
                    offsets.writeLong(i + 1, offsets.readLong(i) + counts.readInt(i));
            }
            Files.delete(tmpCounts);

            // Scatter pass: edges[cursor[cls]++] = instanceId
            try (var cursor  = IndexFile.create(tmpCursor, objectCount, 4);
                 var edges   = IndexFile.create(tmpEdges, objectCount, 4);
                 var offsets = IndexFile.openRead(tmpOffsets);
                 var classOf = IndexFile.openRead(classOfPath)) {
                // Initialize cursor from offsets
                for (int i = 0; i < objectCount; i++)
                    cursor.writeInt(i, (int) offsets.readLong(i));
                // Scatter
                for (int i = 0; i < objectCount; i++) {
                    int cls = classOf.readInt(i);
                    int pos = cursor.readInt(cls);
                    edges.writeInt(pos, i);
                    cursor.writeInt(cls, pos + 1);
                }
            }
            Files.delete(tmpCursor);

            Path finalOffsets = indexDir.resolve("instance-list-offsets.bin");
            Path finalEdges   = indexDir.resolve("instance-list-edges.bin");
            Files.move(tmpOffsets, finalOffsets, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmpEdges,   finalEdges,   StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(tmpCounts);  } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpOffsets); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpCursor);  } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpEdges);   } catch (IOException ignored) {}
        }
    }
}
