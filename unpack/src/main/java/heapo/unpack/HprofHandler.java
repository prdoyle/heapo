package heapo.unpack;

/**
 * Callback interface for HPROF records emitted by {@link HprofReader}.
 * Implementations process records sequentially as they are streamed from disk.
 */
public interface HprofHandler {

    /** Returns the id size in bytes (4 or 8) as set by the header callback. */
    int idSize();

    void header(String magic, int idSize, long timestamp);

    void string(long rawId, String value);

    void loadClass(int classSerial, long classObjectId, long nameStringId);

    void heapDumpEnd();

    // GC roots
    void gcRootUnknown(long objectId);
    void gcRootJniGlobal(long objectId, long jniGlobalRefId);
    void gcRootJniLocal(long objectId, int threadSerial, int frameNumber);
    void gcRootJavaFrame(long objectId, int threadSerial, int frameNumber);
    void gcRootNativeStack(long objectId, int threadSerial);
    void gcRootStickyClass(long objectId);
    void gcRootThreadBlock(long objectId, int threadSerial);
    void gcRootMonitorUsed(long objectId);
    void gcRootThreadObj(long objectId, int threadSerial, int stackSerial);

    // Heap objects
    void classDump(long classObjectId, long superClassId, int instanceSize,
                   long[] fieldNameIds, byte[] fieldTypes);
    void instanceDump(long objectId, long classObjectId, byte[] instanceData);
    void objArrayDump(long objectId, long elementClassId, long[] elements);
    void primArrayDump(long objectId, int elementType, int numElements, byte[] data);
}
