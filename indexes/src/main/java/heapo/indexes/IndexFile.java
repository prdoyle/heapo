package heapo.indexes;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped index file with typed int and long element access.
 * Backed by an {@link Arena}; close this to unmap the file.
 */
public final class IndexFile implements AutoCloseable {

    private final Arena   arena;
    private final MemorySegment segment;

    // All index files are written by DataOutputStream, which is big-endian.
    // Use explicit big-endian layouts so reads on any platform match the written bytes.
    private static final ValueLayout.OfInt  INT_BE  = ValueLayout.JAVA_INT .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_BE = ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN);

    private IndexFile(Arena arena, MemorySegment segment) {
        this.arena   = arena;
        this.segment = segment;
    }

    /** Open an existing file for reading. */
    public static IndexFile openRead(Path path) throws IOException {
        var arena = Arena.ofShared();
        try (var ch = FileChannel.open(path, StandardOpenOption.READ)) {
            var seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            return new IndexFile(arena, seg);
        } catch (IOException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Create (or truncate) a file and map it read-write.
     * Pre-sized to {@code elementCount * elementSizeBytes} bytes, zero-filled.
     */
    public static IndexFile create(Path path, long elementCount, int elementSizeBytes)
            throws IOException {
        long byteSize = elementCount * elementSizeBytes;
        var arena = Arena.ofShared();
        try (var ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            // Extend file to required size
            ch.write(java.nio.ByteBuffer.allocate(1), byteSize - 1);
            var seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, byteSize, arena);
            return new IndexFile(arena, seg);
        } catch (IOException e) {
            arena.close();
            throw e;
        }
    }

    public int  readInt(long index)             { return segment.get(INT_BE,  index * 4L); }
    public void writeInt(long index, int value) { segment.set(INT_BE,  index * 4L, value); }

    public long readLong(long index)              { return segment.get(LONG_BE, index * 8L); }
    public void writeLong(long index, long value) { segment.set(LONG_BE, index * 8L, value); }

    /** Number of int elements (byteSize / 4). */
    public long intCount()  { return segment.byteSize() / 4; }

    /** Number of long elements (byteSize / 8). */
    public long longCount() { return segment.byteSize() / 8; }

    public long byteSize()  { return segment.byteSize(); }

    @Override
    public void close() { arena.close(); }
}
