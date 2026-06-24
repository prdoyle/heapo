package heapo.unpack;

import heapo.util.ExternalMergeSort;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces core index files from an HPROF file.
 * One HPROF scan emits scratch files; post-scan sorting produces the final indexes.
 */
public final class Unpacker {

    // Scratch file record sizes
    static final int ID_MAP_RECORD_BYTES   = 12;  // rawId(8) + denseId(4)
    static final int EDGE_RECORD_BYTES     = 12;  // srcDenseId(4) + dstRawId(8)
    static final int RESOLVED_EDGE_BYTES   =  8;  // srcDenseId(4) + dstDenseId(4)

    private Unpacker() {}

    public static UnpackedHeap unpack(Path hprofFile, Path outputDir) throws IOException {
        return unpack(hprofFile, outputDir, msg -> {});
    }

    public static UnpackedHeap unpack(Path hprofFile, Path outputDir,
                                      java.util.function.Consumer<String> progress) throws IOException {
        Path manifestPath = outputDir.resolve("manifest.json");
        if (Files.exists(manifestPath)) {
            UnpackedHeap cached = tryLoadManifest(outputDir, manifestPath);
            if (cached != null) return cached;
        }

        long hprofBytes = Files.size(hprofFile);
        progress.accept(String.format("Scanning heap dump (%.1f MB)...", hprofBytes / 1_048_576.0));

        Path indexDir  = outputDir.resolve("indexes");
        Path bitsetDir = outputDir.resolve("bitsets");
        Path tempDir   = outputDir.resolve("temp");
        Files.createDirectories(indexDir);
        Files.createDirectories(bitsetDir);
        Files.createDirectories(tempDir);

        Path scratchIdMap       = tempDir.resolve("id-map.bin");
        Path scratchEdges       = tempDir.resolve("edges.bin");
        Path scratchClassOfRaw  = tempDir.resolve("class-of-raw.bin");
        Path primArrayScratch   = tempDir.resolve("prim-array-scratch.bin");
        Path fieldValuesTempDir = tempDir.resolve("fields-tmp");
        Files.createDirectories(fieldValuesTempDir);

        var handler = new ScanHandler(scratchIdMap, scratchEdges, scratchClassOfRaw,
                                      indexDir.resolve("shallow-size.bin"), fieldValuesTempDir,
                                      primArrayScratch);
        new HprofReader(hprofFile).read(handler);
        handler.closeStreams();

        // nextId started at 1, so objectCount = N+1 where N = real objects.
        // Dense ID 0 is the null sentinel; real objects occupy 1..N.
        int objectCount = handler.nextId;   // capacity (N+1)
        int realCount   = objectCount - 1;  // actual object count (N)
        int classCount  = handler.classCount;
        progress.accept(String.format("  %,d objects, %,d classes", realCount, classCount));

        // Sort id-map and split into parallel lookup arrays (realCount entries only)
        sortAndSplitIdMap(scratchIdMap, tempDir, indexDir);
        long[] sortedRawIds = loadLongs(indexDir.resolve("raw-id-lookup-sorted.bin"), realCount);
        int[]  denseIds     = loadInts( indexDir.resolve("raw-id-lookup-dense.bin"),  realCount);

        // Resolve and write remaining index files
        resolveClassOf(scratchClassOfRaw, indexDir.resolve("class-of.bin"),
                       sortedRawIds, denseIds, objectCount);
        buildForwardRefs(scratchEdges, tempDir, indexDir, sortedRawIds, denseIds, objectCount);
        buildSuperClassOf(handler.superClasses, indexDir, sortedRawIds, denseIds, objectCount);
        buildGcRoots(handler.gcRootRawIds, handler.gcRootTypes, indexDir,
                     sortedRawIds, denseIds, objectCount);
        buildIsObjArray(handler.objArrayDenseIds, indexDir, objectCount);
        buildPrimArrayTypes(handler.primArrayElementTypes, indexDir, objectCount);

        // Build field-value index: per-class field files (TYPE_OBJECT + primitives) + schemas
        Path fieldsDir = outputDir.resolve("fields");
        Files.createDirectories(fieldsDir);
        resolveAndWriteFieldValues(handler, fieldValuesTempDir, fieldsDir, sortedRawIds, denseIds);
        writeStaticFieldSchemas(handler, fieldsDir);

        buildPrimArrayIndex(primArrayScratch, indexDir, objectCount);

        deleteIfExists(scratchIdMap, scratchEdges, scratchClassOfRaw);
        try { Files.delete(fieldValuesTempDir); } catch (IOException ignored) {}
        try { Files.delete(tempDir); } catch (IOException ignored) {}

        writeManifest(hprofFile, objectCount, classCount, outputDir.resolve("manifest.json"));
        writeClassNames(handler.classNameIds, handler.strings, sortedRawIds, denseIds,
                        outputDir.resolve("class-names.txt"));
        return new UnpackedHeap(outputDir, objectCount, classCount);
    }

    // ── HPROF scan handler ───────────────────────────────────────────────────

    static final class ScanHandler extends BaseHprofHandler {

        // Small maps — bounded by class/string count
        final Map<Long, String>        strings            = new HashMap<>();
        final Map<Long, Long>          classNameIds       = new HashMap<>();  // classObjectId → nameStringId
        final Map<Long, Long>          superClasses       = new HashMap<>();  // classObjectId → superRawId
        final Map<Long, byte[]>        classFields        = new HashMap<>();  // classObjectId → field types
        final Map<Long, long[]>        classFieldNames    = new HashMap<>();  // classObjectId → field name string IDs
        final Map<Long, Integer>       instanceSizes      = new HashMap<>();  // classObjectId → instanceSize
        final Map<Long, Integer>       classDenseIds      = new HashMap<>();  // classObjectId → dense ID
        final Map<Long, List<String>>  staticFieldNames   = new HashMap<>();  // classObjectId → static obj-ref field names (non-null only, in order)

        // Detected in loadClass (LOAD_CLASS records precede HEAP_DUMP in the file)
        long javaLangClassRawId = 0;

        final List<Long>      gcRootRawIds = new ArrayList<>();
        final Map<Long, Byte> gcRootTypes  = new HashMap<>();

        final List<Integer>    objArrayDenseIds     = new ArrayList<>();
        final Map<Integer, Byte> primArrayElementTypes = new HashMap<>();

        // Dense ID 0 is reserved as the null sentinel. Real objects start at 1.
        int nextId = 1;
        int classCount = 0;

        private final DataOutputStream idMapOut;
        private final DataOutputStream edgesOut;
        private final DataOutputStream classOfRawOut;
        private final DataOutputStream shallowSizeOut;
        private final DataOutputStream primArrayScratchOut;

        // Per-class field value streams: keyed by class dense ID.
        // Scratch records store TYPE_OBJECT as 8-byte raw IDs and primitives as-is.
        // Resolved post-scan into final records with 4-byte dense IDs and native primitives.
        private final Path fieldValuesTempDir;
        final Map<Integer, DataOutputStream> fieldValueStreams = new LinkedHashMap<>();

        ScanHandler(Path scratchIdMap, Path scratchEdges, Path scratchClassOfRaw,
                    Path shallowSizePath, Path fieldValuesTempDir,
                    Path primArrayScratch) throws IOException {
            idMapOut              = dataOut(scratchIdMap);
            edgesOut              = dataOut(scratchEdges);
            classOfRawOut         = dataOut(scratchClassOfRaw);
            shallowSizeOut        = dataOut(shallowSizePath);
            primArrayScratchOut   = dataOut(primArrayScratch);
            this.fieldValuesTempDir = fieldValuesTempDir;

            // Dense ID 0 = null sentinel — pre-write placeholder entries
            classOfRawOut.writeLong(0L);  // classOf null = 0 (no class)
            shallowSizeOut.writeInt(0);   // shallow size of null = 0
        }

        void closeStreams() throws IOException {
            idMapOut.close();
            edgesOut.close();
            classOfRawOut.close();
            shallowSizeOut.close();
            primArrayScratchOut.close();
            for (DataOutputStream out : fieldValueStreams.values()) out.close();
        }

        // ── HprofHandler callbacks ────────────────────────────────────────────

        @Override
        public void string(long rawId, String value) {
            strings.put(rawId, value);
        }

        @Override
        public void loadClass(int classSerial, long classObjectId, long nameStringId) {
            classNameIds.put(classObjectId, nameStringId);
            // Detect java.lang.Class early — LOAD_CLASS records precede HEAP_DUMP,
            // so javaLangClassRawId is set before any classDump callback runs.
            if ("java/lang/Class".equals(strings.get(nameStringId))) {
                javaLangClassRawId = classObjectId;
            }
        }

        @Override
        public void classDump(long classObjectId, long superClassId, int instanceSize,
                              long[] fieldNameIds, byte[] fieldTypes) throws IOException {
            int denseId = nextId++;
            currentClassDenseId = denseId;
            classCount++;
            superClasses.put(classObjectId, superClassId);
            classFields.put(classObjectId, fieldTypes);
            classFieldNames.put(classObjectId, fieldNameIds.clone());
            instanceSizes.put(classObjectId, instanceSize);
            classDenseIds.put(classObjectId, denseId);

            emitIdMap(classObjectId, denseId);
            // Class objects are instances of java.lang.Class
            classOfRawOut.writeLong(javaLangClassRawId);
            shallowSizeOut.writeInt(0);  // class object shallow size not available in HPROF
        }

        // Set in classDump; used by the immediately-following staticObjectField callbacks
        private int currentClassDenseId = -1;

        @Override
        public void staticObjectField(long classObjectId, long nameStringId, long valueRawId) throws IOException {
            // classDump fired first (HprofReader guarantees this), so dense ID is assigned
            if (currentClassDenseId >= 0) emitEdge(currentClassDenseId, valueRawId);
            String name = strings.getOrDefault(nameStringId, "?");
            staticFieldNames.computeIfAbsent(classObjectId, k -> new ArrayList<>()).add(name);
        }

        @Override
        public void instanceDump(long objectId, long classObjectId, byte[] data) throws IOException {
            int denseId = nextId++;
            int size    = instanceSizes.getOrDefault(classObjectId, 0);
            emitIdMap(objectId, denseId);
            classOfRawOut.writeLong(classObjectId);
            shallowSizeOut.writeInt(shallowShift(size));
            parseInstanceRefs(denseId, classObjectId, data);
            writeAllFields(classObjectId, data);
        }

        @Override
        public void objArrayDump(long objectId, long elementClassId, long[] elements) throws IOException {
            int denseId = nextId++;
            int size    = 16 + elements.length * idSize();
            emitIdMap(objectId, denseId);
            classOfRawOut.writeLong(elementClassId);  // approximation: element class ≠ array class
            shallowSizeOut.writeInt(shallowShift(size));
            objArrayDenseIds.add(denseId);
            for (long elem : elements) {
                if (elem != 0) emitEdge(denseId, elem);
            }
        }

        @Override
        public void primArrayDump(long objectId, int elementType, int numElements, byte[] data)
                throws IOException {
            int denseId = nextId++;
            int size    = 16 + numElements * HprofReader.primitiveTypeSize(elementType);
            primArrayElementTypes.put(denseId, (byte) elementType);
            emitIdMap(objectId, denseId);
            classOfRawOut.writeLong(0L);  // no class; classOf=0 (null sentinel) = unknown
            shallowSizeOut.writeInt(shallowShift(size));
            primArrayScratchOut.writeInt(denseId);
            primArrayScratchOut.writeInt(data.length);
            primArrayScratchOut.write(data);
        }

        // ── GC root collectors ─────────────────────────────────────────────────

        @Override public void gcRootUnknown(long id)                    { addRoot(id, HprofReader.HPROF_GC_ROOT_UNKNOWN); }
        @Override public void gcRootJniGlobal(long id, long ref)        { addRoot(id, HprofReader.HPROF_GC_ROOT_JNI_GLOBAL); }
        @Override public void gcRootJniLocal(long id, int ts, int fn)   { addRoot(id, HprofReader.HPROF_GC_ROOT_JNI_LOCAL); }
        @Override public void gcRootJavaFrame(long id, int ts, int fn)  { addRoot(id, HprofReader.HPROF_GC_ROOT_JAVA_FRAME); }
        @Override public void gcRootNativeStack(long id, int ts)        { addRoot(id, HprofReader.HPROF_GC_ROOT_NATIVE_STACK); }
        @Override public void gcRootStickyClass(long id)                { addRoot(id, HprofReader.HPROF_GC_ROOT_STICKY_CLASS); }
        @Override public void gcRootThreadBlock(long id, int ts)        { addRoot(id, HprofReader.HPROF_GC_ROOT_THREAD_BLOCK); }
        @Override public void gcRootMonitorUsed(long id)                { addRoot(id, HprofReader.HPROF_GC_ROOT_MONITOR_USED); }
        @Override public void gcRootThreadObj(long id, int ts, int ss)  { addRoot(id, HprofReader.HPROF_GC_ROOT_THREAD_OBJ); }

        private void addRoot(long rawId, int type) {
            gcRootRawIds.add(rawId);
            gcRootTypes.putIfAbsent(rawId, (byte) type);
        }

        // ── Helpers ─────────────────────────────────────────────────────────────

        private void emitIdMap(long rawId, int denseId) throws IOException {
            idMapOut.writeLong(rawId);
            idMapOut.writeInt(denseId);
        }

        private void emitEdge(int srcDenseId, long dstRawId) throws IOException {
            edgesOut.writeInt(srcDenseId);
            edgesOut.writeLong(dstRawId);
        }

        /**
         * Walk the class hierarchy to find all object-reference fields and emit edges.
         * HPROF lays out instance data starting with the most-derived class's fields,
         * then superclass fields, etc.
         */
        private void parseInstanceRefs(int srcDenseId, long classObjectId, byte[] data)
                throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long curClass = classObjectId;
            while (curClass != 0 && buf.hasRemaining()) {
                byte[] fieldTypes = classFields.get(curClass);
                if (fieldTypes == null) break;
                for (byte ftype : fieldTypes) {
                    int type = ftype & 0xFF;
                    int size = HprofReader.typeSize(type, idSize());
                    if (buf.remaining() < size) return;
                    if (type == HprofReader.TYPE_OBJECT) {
                        long ref = idSize() == 4
                            ? Integer.toUnsignedLong(buf.getInt())
                            : buf.getLong();
                        if (ref != 0) emitEdge(srcDenseId, ref);
                    } else {
                        buf.position(buf.position() + size);
                    }
                }
                Long superRaw = superClasses.get(curClass);
                curClass = (superRaw != null && superRaw != 0) ? superRaw : 0;
            }
        }

        /**
         * Writes all fields (TYPE_OBJECT as 8-byte raw IDs, primitives as-is) to a per-class
         * scratch file. Post-scan, {@link #resolveAndWriteFieldValues} resolves TYPE_OBJECT
         * raw IDs to 4-byte dense IDs and writes the final field-value files.
         */
        private void writeAllFields(long classObjectId, byte[] data) throws IOException {
            Integer classDenseId = classDenseIds.get(classObjectId);
            if (classDenseId == null) return;

            ByteBuffer buf = ByteBuffer.wrap(data);
            var scratch = new ByteArrayOutputStream();
            long curClass = classObjectId;
            while (curClass != 0 && buf.hasRemaining()) {
                byte[] ftypes = classFields.get(curClass);
                if (ftypes == null) break;
                for (byte ftype : ftypes) {
                    int type = ftype & 0xFF;
                    int size = HprofReader.typeSize(type, idSize());
                    if (buf.remaining() < size) return;
                    if (type == HprofReader.TYPE_OBJECT) {
                        // Store raw ID as 8 bytes (big-endian); resolved post-scan to 4-byte dense ID.
                        long rawId = idSize() == 4
                            ? Integer.toUnsignedLong(buf.getInt())
                            : buf.getLong();
                        scratch.write((int)(rawId >> 56));
                        scratch.write((int)(rawId >> 48));
                        scratch.write((int)(rawId >> 40));
                        scratch.write((int)(rawId >> 32));
                        scratch.write((int)(rawId >> 24));
                        scratch.write((int)(rawId >> 16));
                        scratch.write((int)(rawId >> 8));
                        scratch.write((int)rawId);
                    } else {
                        byte[] val = new byte[size];
                        buf.get(val);
                        scratch.write(val, 0, val.length);
                    }
                }
                Long superRaw = superClasses.get(curClass);
                curClass = (superRaw != null && superRaw != 0) ? superRaw : 0;
            }

            if (scratch.size() == 0) return;
            DataOutputStream out = fieldValueStreams.get(classDenseId);
            if (out == null) {
                out = dataOut(fieldValuesTempDir.resolve(classDenseId + ".bin.tmp"));
                fieldValueStreams.put(classDenseId, out);
            }
            scratch.writeTo(out);
        }

        private static int shallowShift(long size) {
            return (int) Math.min(size >>> 3, Integer.MAX_VALUE);
        }
    }

    // ── Post-scan: build sorted ID lookup ────────────────────────────────────

    private static void sortAndSplitIdMap(Path scratchIdMap, Path tempDir, Path indexDir)
            throws IOException {
        Path sortedIdMap = tempDir.resolve("id-map-sorted.bin");
        ExternalMergeSort.sort(
            scratchIdMap, sortedIdMap, tempDir, ID_MAP_RECORD_BYTES,
            (a, b) -> Long.compareUnsigned(a.getLong(0), b.getLong(0)),
            ExternalMergeSort.DEFAULT_MAX_RAM_BYTES
        );
        // Split into two parallel files: sorted rawIds and corresponding denseIds
        Path sortedOut = indexDir.resolve("raw-id-lookup-sorted.bin");
        Path denseOut  = indexDir.resolve("raw-id-lookup-dense.bin");
        try (var in   = dataIn(sortedIdMap);
             var out1 = dataOut(sortedOut);
             var out2 = dataOut(denseOut)) {
            while (true) {
                try {
                    out1.writeLong(in.readLong());
                    out2.writeInt(in.readInt());
                } catch (EOFException e) { break; }
            }
        }
        Files.delete(sortedIdMap);
    }

    // ── Post-scan: class-of ───────────────────────────────────────────────────

    private static void resolveClassOf(Path scratchClassOfRaw, Path classOfPath,
                                       long[] sortedRawIds, int[] denseIds, int objectCount)
            throws IOException {
        try (var in  = dataIn(scratchClassOfRaw);
             var out = dataOut(classOfPath)) {
            for (int i = 0; i < objectCount; i++) {
                long rawClassId   = in.readLong();
                int  classDenseId = resolveDenseId(rawClassId, sortedRawIds, denseIds);
                // 0 = no class (null sentinel, primitive arrays, unresolvable refs)
                out.writeInt(Math.max(classDenseId, 0));
            }
        }
    }

    // ── Post-scan: forward refs (CSR) ─────────────────────────────────────────

    private static void buildForwardRefs(Path scratchEdges, Path tempDir, Path indexDir,
                                         long[] sortedRawIds, int[] denseIds, int objectCount)
            throws IOException {
        // Step 1: sort edges by srcDenseId
        Path sortedEdges = tempDir.resolve("edges-sorted.bin");
        ExternalMergeSort.sort(
            scratchEdges, sortedEdges, tempDir, EDGE_RECORD_BYTES,
            (a, b) -> Integer.compareUnsigned(a.getInt(0), b.getInt(0)),
            ExternalMergeSort.DEFAULT_MAX_RAM_BYTES
        );

        // Step 2: resolve dstRawId → dstDenseId, dropping unresolvable references
        Path resolvedEdges = tempDir.resolve("edges-resolved.bin");
        try (var in  = dataIn(sortedEdges);
             var out = dataOut(resolvedEdges)) {
            while (true) {
                try {
                    int  src    = in.readInt();
                    long dstRaw = in.readLong();
                    int  dst    = resolveDenseId(dstRaw, sortedRawIds, denseIds);
                    if (dst >= 0) {
                        out.writeInt(src);
                        out.writeInt(dst);
                    }
                } catch (EOFException e) { break; }
            }
        }
        Files.delete(sortedEdges);

        // Step 3: count edges per src using +1 offset trick for correct prefix sum
        long[] offsets = new long[objectCount + 1];  // offsets[src+1] = count for src
        try (var in = dataIn(resolvedEdges)) {
            while (true) {
                try {
                    int src = in.readInt();
                    in.readInt();  // dst — skip
                    offsets[src + 1]++;
                } catch (EOFException e) { break; }
            }
        }
        // Inclusive prefix sum → CSR offsets (offsets[0] = 0, offsets[i] = start of edges for obj i)
        for (int i = 1; i <= objectCount; i++) offsets[i] += offsets[i - 1];

        // Step 4: write forward-refs-offsets.bin
        try (var out = dataOut(indexDir.resolve("forward-refs-offsets.bin"))) {
            for (long off : offsets) out.writeLong(off);
        }

        // Step 5: write forward-refs-edges.bin (dst dense IDs in src order)
        try (var in  = dataIn(resolvedEdges);
             var out = dataOut(indexDir.resolve("forward-refs-edges.bin"))) {
            while (true) {
                try {
                    in.readInt();       // src — skip
                    out.writeInt(in.readInt());  // dst
                } catch (EOFException e) { break; }
            }
        }
        Files.delete(resolvedEdges);
    }

    // ── Post-scan: super-class-of ─────────────────────────────────────────────

    private static void buildSuperClassOf(Map<Long, Long> superClasses, Path indexDir,
                                          long[] sortedRawIds, int[] denseIds, int objectCount)
            throws IOException {
        int[] superOf = new int[objectCount];  // 0 = no superclass
        for (var entry : superClasses.entrySet()) {
            int classDense = resolveDenseId(entry.getKey(),   sortedRawIds, denseIds);
            int superDense = resolveDenseId(entry.getValue(), sortedRawIds, denseIds);
            if (classDense >= 0 && classDense < objectCount) {
                superOf[classDense] = Math.max(superDense, 0);
            }
        }
        try (var out = dataOut(indexDir.resolve("super-class-of.bin"))) {
            for (int s : superOf) out.writeInt(s);
        }
    }

    // ── Post-scan: gc-roots ───────────────────────────────────────────────────

    private static void buildGcRoots(List<Long> gcRootRawIds, Map<Long, Byte> gcRootTypes,
                                     Path indexDir, long[] sortedRawIds, int[] denseIds,
                                     int objectCount) throws IOException {
        var seen  = new HashSet<Integer>();
        var roots = new ArrayList<Integer>();
        for (long rawId : gcRootRawIds) {
            int dense = resolveDenseId(rawId, sortedRawIds, denseIds);
            if (dense >= 0 && seen.add(dense)) roots.add(dense);
        }
        try (var out = dataOut(indexDir.resolve("gc-roots.bin"))) {
            for (int id : roots) out.writeInt(id);
        }

        // gc-root-type-map.bin: one byte per dense ID, 0 = not a root, else HPROF_GC_ROOT_* type
        byte[] typeMap = new byte[objectCount];
        for (var entry : gcRootTypes.entrySet()) {
            int dense = resolveDenseId(entry.getKey(), sortedRawIds, denseIds);
            if (dense >= 0 && dense < objectCount) typeMap[dense] = entry.getValue();
        }
        Files.write(indexDir.resolve("gc-root-type-map.bin"), typeMap);
    }

    // ── Post-scan: field values ───────────────────────────────────────────────

    /**
     * Resolves scratch field-value files (TYPE_OBJECT as 8-byte raw IDs) into final files
     * (TYPE_OBJECT as 4-byte dense IDs, 0 = null; primitives copied as-is).
     */
    private static void resolveAndWriteFieldValues(ScanHandler handler, Path tempDir, Path fieldsDir,
                                                    long[] sortedRawIds, int[] denseIds)
            throws IOException {
        Map<Integer, Long> denseToRaw = new HashMap<>();
        for (var e : handler.classDenseIds.entrySet()) denseToRaw.put(e.getValue(), e.getKey());

        for (int classDenseId : handler.fieldValueStreams.keySet()) {
            Path src = tempDir.resolve(classDenseId + ".bin.tmp");
            if (!Files.exists(src)) continue;
            Long rawClassId = denseToRaw.get(classDenseId);
            if (rawClassId == null) continue;

            // Compute scratch and final record layouts, and collect field names for the schema.
            // Schema is written from the same fieldInfos that governs the binary layout —
            // so schema and data are provably derived from the same computation.
            var fieldInfos = new ArrayList<int[]>();  // [scratchOffset, finalOffset, typeCode, scratchSize]
            var fieldNames = new ArrayList<String>();
            int scratchOff = 0, finalOff = 0, fieldIdx = 0;
            long curClass = rawClassId;
            while (curClass != 0) {
                byte[] ftypes   = handler.classFields.get(curClass);
                long[] fnameIds = handler.classFieldNames.get(curClass);
                if (ftypes == null) break;
                for (int i = 0; i < ftypes.length; i++) {
                    int type = ftypes[i] & 0xFF;
                    String name = (fnameIds != null && i < fnameIds.length)
                        ? handler.strings.getOrDefault(fnameIds[i], "field_" + fieldIdx)
                        : "field_" + fieldIdx;
                    fieldNames.add(name);
                    if (type == HprofReader.TYPE_OBJECT) {
                        fieldInfos.add(new int[]{scratchOff, finalOff, type, 8});
                        scratchOff += 8;
                        finalOff   += 4;
                    } else {
                        int sz = HprofReader.primitiveTypeSize(type);
                        fieldInfos.add(new int[]{scratchOff, finalOff, type, sz});
                        scratchOff += sz;
                        finalOff   += sz;
                    }
                    fieldIdx++;
                }
                Long superRaw = handler.superClasses.get(curClass);
                curClass = (superRaw != null && superRaw != 0) ? superRaw : 0;
            }
            if (fieldInfos.isEmpty()) continue;

            int scratchRecordSize = scratchOff;
            int finalRecordSize   = finalOff;
            long srcSize = Files.size(src);
            if (srcSize % scratchRecordSize != 0)
                throw new IOException("Field scratch file for class " + classDenseId
                    + " is " + srcSize + " bytes, not a multiple of scratch record size "
                    + scratchRecordSize + " — writeAllFields wrote short records (buf exhausted before end of hierarchy)");
            long numInstances = srcSize / scratchRecordSize;

            // Write schema derived from the same field layout used for the binary
            var schemaLines = new ArrayList<String>(fieldInfos.size());
            for (int i = 0; i < fieldInfos.size(); i++)
                schemaLines.add(fieldNames.get(i) + "\t" + fieldInfos.get(i)[2]);
            Files.writeString(fieldsDir.resolve(classDenseId + ".schema"),
                String.join("\n", schemaLines) + "\n");

            byte[] scratchBuf = new byte[scratchRecordSize];
            byte[] finalBuf   = new byte[finalRecordSize];
            Path dst = fieldsDir.resolve(classDenseId + ".bin");

            try (var in  = dataIn(src);
                 var out = dataOut(dst)) {
                for (long i = 0; i < numInstances; i++) {
                    in.readFully(scratchBuf);
                    for (int[] fi : fieldInfos) {
                        int sOff = fi[0], fOff = fi[1], type = fi[2], sz = fi[3];
                        if (type == HprofReader.TYPE_OBJECT) {
                            long rawId = 0;
                            for (int j = 0; j < 8; j++) rawId = (rawId << 8) | (scratchBuf[sOff + j] & 0xFF);
                            int densId = (rawId == 0) ? 0 : resolveDenseId(rawId, sortedRawIds, denseIds);
                            if (densId < 0) densId = 0;  // unresolvable → null sentinel
                            finalBuf[fOff]     = (byte)(densId >> 24);
                            finalBuf[fOff + 1] = (byte)(densId >> 16);
                            finalBuf[fOff + 2] = (byte)(densId >> 8);
                            finalBuf[fOff + 3] = (byte)densId;
                        } else {
                            System.arraycopy(scratchBuf, sOff, finalBuf, fOff, sz);
                        }
                    }
                    out.write(finalBuf);
                }
            }
            Files.delete(src);
        }
    }

    private static void writeFieldSchemas(ScanHandler handler, Path fieldsDir) throws IOException {
        // Inverse map: classDenseId → classObjectId (raw)
        Map<Integer, Long> denseToRaw = new HashMap<>();
        for (var e : handler.classDenseIds.entrySet()) denseToRaw.put(e.getValue(), e.getKey());

        for (var entry : denseToRaw.entrySet()) {
            int classDenseId = entry.getKey();
            Long rawId = entry.getValue();

            List<String> lines = new ArrayList<>();
            long curClass = rawId;
            while (curClass != 0) {
                byte[] ftypes   = handler.classFields.get(curClass);
                long[] fnameIds = handler.classFieldNames.get(curClass);
                if (ftypes == null) break;
                for (int i = 0; i < ftypes.length; i++) {
                    int type = ftypes[i] & 0xFF;
                    String name = (fnameIds != null && i < fnameIds.length)
                        ? handler.strings.getOrDefault(fnameIds[i], "field_" + i)
                        : "field_" + i;
                    lines.add(name + "\t" + type);
                }
                Long superRaw = handler.superClasses.get(curClass);
                curClass = (superRaw != null && superRaw != 0) ? superRaw : 0;
            }

            if (!lines.isEmpty()) {
                Files.writeString(fieldsDir.resolve(classDenseId + ".schema"),
                    String.join("\n", lines) + "\n");
            }
        }
    }

    private static void writeStaticFieldSchemas(ScanHandler handler, Path fieldsDir) throws IOException {
        for (var entry : handler.staticFieldNames.entrySet()) {
            long classObjectId = entry.getKey();
            List<String> names = entry.getValue();
            Integer denseId = handler.classDenseIds.get(classObjectId);
            if (denseId == null || names.isEmpty()) continue;
            Files.writeString(fieldsDir.resolve(denseId + ".static-schema"),
                String.join("\n", names) + "\n");
        }
    }

    // ── Post-scan: prim-array data index ─────────────────────────────────────

    /**
     * Builds two index files from the prim-array scratch:
     * <ul>
     *   <li>{@code indexes/prim-array-offsets.bin}: one long per dense ID; value = byte offset in
     *       data file where {@code [int length][byte[] data]} starts, or {@code -1} if not a prim array.</li>
     *   <li>{@code indexes/prim-array-data.bin}: packed records {@code [int length][byte[] data]}
     *       for all primitive arrays, in dense-ID order.</li>
     * </ul>
     */
    private static void buildPrimArrayIndex(Path scratchPath, Path indexDir, int objectCount)
            throws IOException {
        long[] offsets = new long[objectCount];
        Arrays.fill(offsets, -1L);

        Path dataPath = indexDir.resolve("prim-array-data.bin");
        long dataOffset = 0;

        try (var in  = dataIn(scratchPath);
             var out = dataOut(dataPath)) {
            while (true) {
                try {
                    int    denseId = in.readInt();
                    int    length  = in.readInt();
                    byte[] bytes   = in.readNBytes(length);
                    offsets[denseId] = dataOffset;
                    out.writeInt(length);
                    out.write(bytes);
                    // Pad to 4-byte alignment so readIntAt() works for subsequent records.
                    int pad = (4 - (length & 3)) & 3;
                    for (int i = 0; i < pad; i++) out.writeByte(0);
                    dataOffset += 4L + length + pad;
                } catch (EOFException e) { break; }
            }
        }

        try (var out = dataOut(indexDir.resolve("prim-array-offsets.bin"))) {
            for (long off : offsets) out.writeLong(off);
        }

        Files.delete(scratchPath);
    }

    private static void buildIsObjArray(List<Integer> ids, Path indexDir, int objectCount) throws IOException {
        byte[] arr = new byte[objectCount];
        for (int id : ids) if (id >= 0 && id < objectCount) arr[id] = 1;
        Files.write(indexDir.resolve("is-obj-array.bin"), arr);
    }

    private static void buildPrimArrayTypes(Map<Integer, Byte> types, Path indexDir, int objectCount) throws IOException {
        byte[] arr = new byte[objectCount];
        for (var e : types.entrySet()) {
            int id = e.getKey();
            if (id >= 0 && id < objectCount) arr[id] = e.getValue();
        }
        Files.write(indexDir.resolve("prim-array-types.bin"), arr);
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    private static UnpackedHeap tryLoadManifest(Path outputDir, Path manifestPath) {
        try {
            String json = Files.readString(manifestPath);
            int objectCount = extractJsonInt(json, "objectCount");
            int classCount  = extractJsonInt(json, "classCount");
            return new UnpackedHeap(outputDir, objectCount, classCount);
        } catch (Exception e) {
            return null;
        }
    }

    private static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\": ";
        int start = json.indexOf(search);
        if (start < 0) throw new IllegalArgumentException("Key not found: " + key);
        start += search.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private static void writeManifest(Path hprofFile, int objectCount, int classCount,
                                      Path manifestPath) throws IOException {
        String fingerprint = computeFingerprint(hprofFile);
        String json = """
                {
                  "hprofFingerprint": "%s",
                  "objectCount": %d,
                  "classCount": %d,
                  "idWidth": 32
                }
                """.formatted(fingerprint, objectCount, classCount);
        Files.writeString(manifestPath, json);
    }

    /** SHA-256 of the first 64 KB of the file concatenated with its total size as 8 bytes. */
    private static String computeFingerprint(Path hprofFile) throws IOException {
        try {
            var  md     = MessageDigest.getInstance("SHA-256");
            byte[] hdr  = new byte[64 * 1024];
            int n;
            try (var in = Files.newInputStream(hprofFile)) {
                n = in.readNBytes(hdr, 0, hdr.length);
            }
            md.update(hdr, 0, n);
            var sizeBytes = ByteBuffer.allocate(8).putLong(Files.size(hprofFile)).array();
            md.update(sizeBytes);
            var sb = new StringBuilder("sha256:");
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    /** Writes class-names.txt: one line per class, format "<denseId>\t<hprofSlashedName>". */
    private static void writeClassNames(Map<Long, Long> classNameIds, Map<Long, String> strings,
                                        long[] sortedRawIds, int[] denseIds, Path outPath)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (var entry : classNameIds.entrySet()) {
            int denseId = resolveDenseId(entry.getKey(), sortedRawIds, denseIds);
            if (denseId < 0) continue;
            String name = strings.get(entry.getValue());
            if (name == null) continue;
            lines.add(denseId + "\t" + name);
        }
        Files.writeString(outPath, String.join("\n", lines) + "\n");
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** Binary search for rawId in sorted lookup; returns dense ID or -1 if not found. */
    public static int resolveDenseId(long rawId, long[] sortedRawIds, int[] denseIds) {
        if (rawId == 0) return -1;
        int idx = Arrays.binarySearch(sortedRawIds, rawId);
        return idx >= 0 ? denseIds[idx] : -1;
    }

    private static long[] loadLongs(Path path, int count) throws IOException {
        long[] arr = new long[count];
        try (var in = dataIn(path)) {
            for (int i = 0; i < count; i++) arr[i] = in.readLong();
        }
        return arr;
    }

    private static int[] loadInts(Path path, int count) throws IOException {
        int[] arr = new int[count];
        try (var in = dataIn(path)) {
            for (int i = 0; i < count; i++) arr[i] = in.readInt();
        }
        return arr;
    }

    private static DataInputStream dataIn(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16));
    }

    private static DataOutputStream dataOut(Path path) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING), 1 << 16));
    }

    private static void deleteIfExists(Path... paths) {
        for (Path p : paths) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
    }
}
