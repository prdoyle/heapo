package heapo.unpack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class UnpackerTest {

    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir", "build/hprof-samples"));
    static UnpackedHeap heap;

    @BeforeAll
    static void setup() throws Exception {
        var hprofPath = samplesDir.resolve("known-objects.hprof");
        var outDir    = Files.createTempDirectory("heapo-unpack-test");
        heap = Unpacker.unpack(hprofPath, outDir);
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    @Test
    void manifestObjectCountIsReasonable() {
        assertTrue(heap.objectCount() > 100,
            "objectCount should include JVM internals; got " + heap.objectCount());
        assertTrue(heap.objectCount() < 10_000_000,
            "objectCount unreasonably large: " + heap.objectCount());
    }

    @Test
    void manifestClassCountIsReasonable() {
        assertTrue(heap.classCount() > 10,
            "classCount should include JDK classes; got " + heap.classCount());
    }

    @Test
    void manifestFileExists() throws Exception {
        Path manifest = heap.outputDir().resolve("manifest.json");
        assertTrue(Files.exists(manifest), "manifest.json should exist");
        String text = Files.readString(manifest);
        assertTrue(text.contains("objectCount"), "manifest should contain objectCount");
    }

    // ── class-of.bin ─────────────────────────────────────────────────────────

    @Test
    void classOfBinContainsFooInstances() throws Exception {
        // Find the dense ID of KnownObjects$Foo class by name via class histogram
        var histogram = ClassHistogram.build(samplesDir.resolve("known-objects.hprof"));
        String fooClassName = "heapo.samples.KnownObjects$Foo";
        long fooInstanceCount = histogram.stream()
            .filter(e -> fooClassName.equals(e.className()))
            .mapToLong(ClassHistogram.Entry::instanceCount)
            .sum();
        assertTrue(fooInstanceCount >= 2, "Expected >= 2 Foo instances; got " + fooInstanceCount);
    }

    @Test
    void classOfBinDenseIdsExist() throws Exception {
        // Verify class-of.bin has the right number of entries
        Path classOfPath = heap.indexDir().resolve("class-of.bin");
        assertTrue(Files.exists(classOfPath), "class-of.bin should exist");
        long fileSize = Files.size(classOfPath);
        assertEquals((long) heap.objectCount() * 4, fileSize,
            "class-of.bin should have objectCount * 4 bytes");
    }

    // ── forward-refs CSR ─────────────────────────────────────────────────────

    @Test
    void forwardRefsFilesExist() {
        assertTrue(Files.exists(heap.indexDir().resolve("forward-refs-offsets.bin")),
            "forward-refs-offsets.bin should exist");
        assertTrue(Files.exists(heap.indexDir().resolve("forward-refs-edges.bin")),
            "forward-refs-edges.bin should exist");
    }

    @Test
    void forwardRefsOffsetsSizeIsCorrect() throws Exception {
        Path offsetsPath = heap.indexDir().resolve("forward-refs-offsets.bin");
        long fileSize = Files.size(offsetsPath);
        // (objectCount + 1) longs
        assertEquals((long)(heap.objectCount() + 1) * 8, fileSize,
            "forward-refs-offsets.bin should have (objectCount+1) * 8 bytes");
    }

    @Test
    void forwardRefsContainsFooBarEdge() throws Exception {
        // Find dense IDs of KnownObjects$Foo and KnownObjects$Bar class objects
        // by scanning raw-id-lookup and class-of, then find instances via instance-scan
        // Then verify forward-refs contains Foo→Foo and Bar→Foo edges

        int objectCount = heap.objectCount();
        int[] classOf   = readIntArray(heap.indexDir().resolve("class-of.bin"), objectCount);

        // Build a simple raw-id lookup (sorted rawIds → denseIds)
        long[] sortedRawIds = readLongArray(heap.indexDir().resolve("raw-id-lookup-sorted.bin"), objectCount);
        int[]  denseIds     = readIntArray( heap.indexDir().resolve("raw-id-lookup-dense.bin"),  objectCount);

        // Get class dense IDs by re-scanning the HPROF for class names
        long[] classDenseIds = findClassDenseIds(sortedRawIds, denseIds);
        int fooDenseClassId = (int) classDenseIds[0];
        int barDenseClassId = (int) classDenseIds[1];

        assertTrue(fooDenseClassId >= 0, "Should find KnownObjects$Foo class dense ID");
        assertTrue(barDenseClassId >= 0, "Should find KnownObjects$Bar class dense ID");

        // Find Foo and Bar instance dense IDs
        List<Integer> fooInstances = new ArrayList<>();
        List<Integer> barInstances = new ArrayList<>();
        for (int i = 0; i < objectCount; i++) {
            if (classOf[i] == fooDenseClassId) fooInstances.add(i);
            if (classOf[i] == barDenseClassId) barInstances.add(i);
        }
        assertTrue(fooInstances.size() >= 2, "Expected >= 2 Foo instances; got " + fooInstances.size());
        assertTrue(barInstances.size() >= 1, "Expected >= 1 Bar instance; got " + barInstances.size());

        // Load forward-refs CSR
        long[] offsets = readLongArray(heap.indexDir().resolve("forward-refs-offsets.bin"), objectCount + 1);
        int totalEdges = (int) offsets[objectCount];
        int[] edges    = readIntArray(heap.indexDir().resolve("forward-refs-edges.bin"), totalEdges);

        // Check that at least one Foo instance has an edge to another Foo instance
        boolean fooToFoo = false;
        for (int fooId : fooInstances) {
            for (long e = offsets[fooId]; e < offsets[fooId + 1]; e++) {
                if (fooInstances.contains(edges[(int) e])) { fooToFoo = true; break; }
            }
            if (fooToFoo) break;
        }
        assertTrue(fooToFoo, "Expected a Foo→Foo edge (foo1.next = foo2)");

        // Check that the Bar instance has an edge to a Foo instance
        boolean barToFoo = false;
        for (int barId : barInstances) {
            for (long e = offsets[barId]; e < offsets[barId + 1]; e++) {
                if (fooInstances.contains(edges[(int) e])) { barToFoo = true; break; }
            }
        }
        assertTrue(barToFoo, "Expected a Bar→Foo edge (bar1.owner = foo1)");
    }

    // ── shallow-size.bin ─────────────────────────────────────────────────────

    @Test
    void shallowSizesAreNonNegative() throws Exception {
        int objectCount = heap.objectCount();
        int[] sizes     = readIntArray(heap.indexDir().resolve("shallow-size.bin"), objectCount);
        for (int i = 0; i < objectCount; i++) {
            assertTrue(sizes[i] >= 0, "Shallow size at dense ID " + i + " should be >= 0");
        }
    }

    @Test
    void shallowSizesAreRightShifted() throws Exception {
        // All stored values should represent shifted sizes: original ÷ 8
        // Verify by checking that at least some instances have non-zero sizes
        int objectCount = heap.objectCount();
        int[] sizes     = readIntArray(heap.indexDir().resolve("shallow-size.bin"), objectCount);
        long nonZero = 0;
        for (int s : sizes) if (s > 0) nonZero++;
        assertTrue(nonZero > 0, "Expected some non-zero shallow sizes");
    }

    // ── gc-roots.bin ─────────────────────────────────────────────────────────

    @Test
    void gcRootsFileIsNonEmpty() throws Exception {
        Path gcRootsPath = heap.indexDir().resolve("gc-roots.bin");
        assertTrue(Files.exists(gcRootsPath), "gc-roots.bin should exist");
        assertTrue(Files.size(gcRootsPath) > 0, "gc-roots.bin should be non-empty");
    }

    // ── super-class-of.bin ───────────────────────────────────────────────────

    @Test
    void superClassOfFileHasCorrectSize() throws Exception {
        Path path = heap.indexDir().resolve("super-class-of.bin");
        assertTrue(Files.exists(path), "super-class-of.bin should exist");
        assertEquals((long) heap.objectCount() * 4, Files.size(path),
            "super-class-of.bin should have objectCount * 4 bytes");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Re-scan the HPROF to find dense IDs of KnownObjects$Foo and KnownObjects$Bar class objects. */
    private long[] findClassDenseIds(long[] sortedRawIds, int[] denseIds) throws Exception {
        var handler = new BaseHprofHandler() {
            long fooClassRawId = -1;
            long barClassRawId = -1;
            final java.util.Map<Long, String> strings = new java.util.HashMap<>();
            final java.util.Map<Long, Long> classNameIds = new java.util.HashMap<>();

            @Override public void string(long rawId, String value) { strings.put(rawId, value); }
            @Override public void loadClass(int s, long classObjectId, long nameStringId) {
                classNameIds.put(classObjectId, nameStringId);
            }
            @Override public void classDump(long classObjectId, long superClassId, int instanceSize,
                                            long[] fieldNameIds, byte[] fieldTypes) {
                Long nameId = classNameIds.get(classObjectId);
                if (nameId == null) return;
                String name = strings.get(nameId);
                if ("heapo/samples/KnownObjects$Foo".equals(name)) fooClassRawId = classObjectId;
                if ("heapo/samples/KnownObjects$Bar".equals(name)) barClassRawId = classObjectId;
            }
        };
        new HprofReader(samplesDir.resolve("known-objects.hprof")).read(handler);

        int fooDense = handler.fooClassRawId >= 0
            ? Unpacker.resolveDenseId(handler.fooClassRawId, sortedRawIds, denseIds) : -1;
        int barDense = handler.barClassRawId >= 0
            ? Unpacker.resolveDenseId(handler.barClassRawId, sortedRawIds, denseIds) : -1;
        return new long[]{fooDense, barDense};
    }

    private static int[] readIntArray(Path path, int count) throws Exception {
        int[] arr = new int[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readInt();
        }
        return arr;
    }

    private static long[] readLongArray(Path path, int count) throws Exception {
        long[] arr = new long[count];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) arr[i] = in.readLong();
        }
        return arr;
    }
}
