package heapo.indexes;

import heapo.util.IndexFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Read-only view of a CSR (Compressed Sparse Row) adjacency pair.
 * The offsets file contains (vertexCount+1) longs; the edges file contains int destination IDs.
 */
public final class CsrReader implements AutoCloseable {

    private final IndexFile offsets;
    private final IndexFile edges;

    public CsrReader(Path offsetsPath, Path edgesPath) throws IOException {
        offsets = IndexFile.openRead(offsetsPath);
        edges   = IndexFile.openRead(edgesPath);
    }

    /** Inclusive start offset into the edges array for vertex {@code v}. */
    public long start(int v) { return offsets.readLong(v); }

    /** Exclusive end offset into the edges array for vertex {@code v}. */
    public long end(int v)   { return offsets.readLong(v + 1L); }

    /** Number of edges for vertex {@code v}. */
    public long degree(int v) { return end(v) - start(v); }

    /** Destination vertex at absolute edge position {@code pos} in the edges array. */
    public int edge(long pos) { return edges.readInt(pos); }

    /** Total number of edges across all vertices. */
    public long totalEdges() { return edges.intCount(); }

    @Override
    public void close() throws IOException {
        offsets.close();
        edges.close();
    }
}
