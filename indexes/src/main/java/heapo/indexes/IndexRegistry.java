package heapo.indexes;

import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.file.*;

/**
 * Manages lazy building and cached access to all derived indexes.
 * Each index is built on first access if not already present, then atomically renamed.
 */
public final class IndexRegistry implements AutoCloseable {

    private final UnpackedHeap heap;
    private final Path         indexDir;
    private final Path         tempDir;

    public IndexRegistry(UnpackedHeap heap) throws IOException {
        this.heap     = heap;
        this.indexDir = heap.indexDir();
        this.tempDir  = heap.outputDir().resolve("temp");
        Files.createDirectories(tempDir);
    }

    // ── Ensure builders ───────────────────────────────────────────────────────

    public void ensureInstanceList() throws IOException {
        if (exists("instance-list-offsets.bin", "instance-list-edges.bin")) return;
        InstanceListBuilder.build(heap, tempDir);
    }

    public void ensureReverseRefs() throws IOException {
        if (exists("reverse-refs-offsets.bin", "reverse-refs-edges.bin")) return;
        ReverseRefsBuilder.build(heap, tempDir);
    }

    public void ensureDfsTree() throws IOException {
        if (exists("dfs-num.bin", "dfs-vertex.bin", "dfs-parent.bin")) return;
        DfsBuilder.build(heap, tempDir);
    }

    public void ensureDominators() throws IOException {
        if (exists("idom.bin")) return;
        ensureReverseRefs();
        ensureDfsTree();
        DominatorBuilder.build(heap, tempDir);
    }

    public void ensureRetainedSizes() throws IOException {
        if (exists("retained-size.bin", "retained-size-rank.bin",
                   "dominator-children-offsets.bin", "dominator-children-edges.bin",
                   "dominator-subtree-size.bin")) return;
        ensureDominators();
        ensureDfsTree();
        RetainedSizeBuilder.build(heap, tempDir);
    }

    /** Build all derived indexes in dependency order. */
    public void buildAll() throws IOException {
        ensureInstanceList();
        ensureReverseRefs();
        ensureDfsTree();
        ensureDominators();
        ensureRetainedSizes();
    }

    // ── Reader accessors ──────────────────────────────────────────────────────

    public CsrReader openForwardRefs() throws IOException {
        return new CsrReader(
            indexDir.resolve("forward-refs-offsets.bin"),
            indexDir.resolve("forward-refs-edges.bin"));
    }

    public CsrReader openReverseRefs() throws IOException {
        ensureReverseRefs();
        return new CsrReader(
            indexDir.resolve("reverse-refs-offsets.bin"),
            indexDir.resolve("reverse-refs-edges.bin"));
    }

    public CsrReader openInstanceList() throws IOException {
        ensureInstanceList();
        return new CsrReader(
            indexDir.resolve("instance-list-offsets.bin"),
            indexDir.resolve("instance-list-edges.bin"));
    }

    public IndexFile openClassOf() throws IOException {
        return IndexFile.openRead(indexDir.resolve("class-of.bin"));
    }

    public IndexFile openSuperClassOf() throws IOException {
        return IndexFile.openRead(indexDir.resolve("super-class-of.bin"));
    }

    public IndexFile openShallowSize() throws IOException {
        return IndexFile.openRead(indexDir.resolve("shallow-size.bin"));
    }

    public IndexFile openDfsNum() throws IOException {
        ensureDfsTree();
        return IndexFile.openRead(indexDir.resolve("dfs-num.bin"));
    }

    public IndexFile openDfsVertex() throws IOException {
        ensureDfsTree();
        return IndexFile.openRead(indexDir.resolve("dfs-vertex.bin"));
    }

    public IndexFile openDfsParent() throws IOException {
        ensureDfsTree();
        return IndexFile.openRead(indexDir.resolve("dfs-parent.bin"));
    }

    public IndexFile openIdom() throws IOException {
        ensureDominators();
        return IndexFile.openRead(indexDir.resolve("idom.bin"));
    }

    public IndexFile openRetainedSize() throws IOException {
        ensureRetainedSizes();
        return IndexFile.openRead(indexDir.resolve("retained-size.bin"));
    }

    public IndexFile openRetainedSizeRank() throws IOException {
        ensureRetainedSizes();
        return IndexFile.openRead(indexDir.resolve("retained-size-rank.bin"));
    }

    public IndexFile openDominatorSubtreeSize() throws IOException {
        ensureRetainedSizes();
        return IndexFile.openRead(indexDir.resolve("dominator-subtree-size.bin"));
    }

    public CsrReader openDominatorChildren() throws IOException {
        ensureRetainedSizes();
        return new CsrReader(
            indexDir.resolve("dominator-children-offsets.bin"),
            indexDir.resolve("dominator-children-edges.bin"));
    }

    @Override public void close() { /* readers are opened/closed by callers */ }

    // ── Private ───────────────────────────────────────────────────────────────

    private boolean exists(String... names) {
        for (String name : names) {
            if (!Files.exists(indexDir.resolve(name))) return false;
        }
        return true;
    }
}
