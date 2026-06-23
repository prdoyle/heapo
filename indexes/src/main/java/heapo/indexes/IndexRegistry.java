package heapo.indexes;

import heapo.unpack.HprofReader;
import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

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
        buildAll(msg -> {});
    }

    /**
     * Build all derived indexes in dependency order, reporting each phase that
     * actually needs building to {@code progress}. Already-built phases are silent.
     */
    public void buildAll(java.util.function.Consumer<String> progress) throws IOException {
        if (!exists("instance-list-offsets.bin", "instance-list-edges.bin")) {
            progress.accept("  Building instance list...");
            ensureInstanceList();
        }
        if (!exists("reverse-refs-offsets.bin", "reverse-refs-edges.bin")) {
            progress.accept("  Building reverse refs...");
            ensureReverseRefs();
        }
        if (!exists("dfs-num.bin", "dfs-vertex.bin", "dfs-parent.bin")) {
            progress.accept("  Building DFS tree...");
            ensureDfsTree();
        }
        if (!exists("idom.bin")) {
            progress.accept("  Building dominator tree...");
            ensureDominators();
        }
        if (!exists("retained-size.bin", "retained-size-rank.bin",
                    "dominator-children-offsets.bin", "dominator-children-edges.bin",
                    "dominator-subtree-size.bin")) {
            progress.accept("  Computing retained sizes...");
            ensureRetainedSizes();
        }
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

    /** Opens the list of GC root dense IDs (written during unpack, always present). */
    public IndexFile openGcRoots() throws IOException {
        return IndexFile.openRead(indexDir.resolve("gc-roots.bin"));
    }

    /**
     * Returns the raw bytes of gc-root-type-map.bin (one byte per dense ID, 0 = not a root),
     * or null if the file does not exist (indexes built before this feature was added).
     */
    public byte[] loadGcRootTypeMap() throws IOException {
        Path p = indexDir.resolve("gc-root-type-map.bin");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    /** Returns one byte per dense ID: 1 if object array, 0 otherwise. Null if file absent (old index). */
    public byte[] loadIsObjArray() throws IOException {
        Path p = indexDir.resolve("is-obj-array.bin");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    /** Returns one byte per dense ID: HprofReader element type code if primitive array, 0 otherwise. Null if file absent. */
    public byte[] loadPrimArrayTypes() throws IOException {
        Path p = indexDir.resolve("prim-array-types.bin");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    // ── Field-value index ─────────────────────────────────────────────────────

    /**
     * Describes one primitive field in a class's field-value record.
     * {@code byteOffset} is the byte offset within each instance's packed record.
     */
    public record FieldDef(String name, int typeCode, int byteOffset) {}

    /**
     * Loads the field schema for the given class dense ID.
     * Returns an empty list if no primitive field data was indexed for this class.
     * Fields are listed in HPROF declaration order (most-derived class first).
     */
    public List<FieldDef> loadFieldSchema(int classDenseId) throws IOException {
        Path schemaPath = heap.outputDir().resolve("fields/" + classDenseId + ".schema");
        if (!Files.exists(schemaPath)) return List.of();
        List<FieldDef> defs = new ArrayList<>();
        int offset = 0;
        for (String line : Files.readAllLines(schemaPath)) {
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) continue;
            int typeCode = Integer.parseInt(parts[1].trim());
            if (typeCode == HprofReader.TYPE_OBJECT) {
                defs.add(new FieldDef(parts[0], typeCode, -1));
            } else {
                defs.add(new FieldDef(parts[0], typeCode, offset));
                offset += HprofReader.primitiveTypeSize(typeCode);
            }
        }
        return Collections.unmodifiableList(defs);
    }

    /**
     * Returns the static object-reference field names for the given class dense ID, in the order
     * edges were emitted (non-null statics only). Returns an empty list if no file exists.
     */
    public List<String> loadStaticFieldNames(int classDenseId) throws IOException {
        Path p = heap.outputDir().resolve("fields/" + classDenseId + ".static-schema");
        if (!Files.exists(p)) return List.of();
        return Files.readAllLines(p).stream()
                .filter(l -> !l.isBlank())
                .toList();
    }

    /** Total size in bytes of one instance's packed primitive record for the given schema. */
    public static int fieldRecordSize(List<FieldDef> schema) {
        for (int i = schema.size() - 1; i >= 0; i--) {
            FieldDef f = schema.get(i);
            if (f.typeCode() != HprofReader.TYPE_OBJECT)
                return f.byteOffset() + HprofReader.primitiveTypeSize(f.typeCode());
        }
        return 0;
    }

    /**
     * Opens the packed primitive field-value file for the given class dense ID.
     * Record {@code i} starts at byte offset {@code i * fieldRecordSize(schema)}.
     */
    public IndexFile openFieldValues(int classDenseId) throws IOException {
        return IndexFile.openRead(heap.outputDir().resolve("fields/" + classDenseId + ".bin"));
    }

    // ── Primitive-array data index ────────────────────────────────────────────

    /** Returns true if the prim-array-data index was built by this unpack. */
    public boolean hasPrimArrayIndex() {
        return Files.exists(indexDir.resolve("prim-array-offsets.bin"));
    }

    /**
     * Opens the prim-array offsets index.
     * Entry {@code i} is the byte offset in {@link #openPrimArrayData()} where object {@code i}'s
     * prim-array data starts ({@code [int length][byte[] data]}), or {@code -1} if object {@code i}
     * is not a primitive array.
     */
    public IndexFile openPrimArrayOffsets() throws IOException {
        return IndexFile.openRead(indexDir.resolve("prim-array-offsets.bin"));
    }

    /**
     * Opens the prim-array data file.
     * Each record: {@code [4-byte int length][length bytes data]}.
     * Use {@link #openPrimArrayOffsets()} to get the byte offset for a specific object.
     */
    public IndexFile openPrimArrayData() throws IOException {
        return IndexFile.openRead(indexDir.resolve("prim-array-data.bin"));
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
