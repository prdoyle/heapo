package heapo.unpack;

import java.io.IOException;

/**
 * Callback interface for HPROF records emitted by {@link HprofReader}.
 * Implementations process records sequentially as they are streamed from disk.
 * All methods may throw {@link IOException} to propagate I/O errors from handlers
 * that write to files during the scan.
 */
public interface HprofHandler {

    /** Returns the id size in bytes (4 or 8) as set by the header callback. */
    int idSize();

    void header(String magic, int idSize, long timestamp) throws IOException;

    void string(long rawId, String value) throws IOException;

    void loadClass(int classSerial, long classObjectId, long nameStringId) throws IOException;

    void heapDumpEnd() throws IOException;

    // GC roots
    void gcRootUnknown(long objectId) throws IOException;
    void gcRootJniGlobal(long objectId, long jniGlobalRefId) throws IOException;
    void gcRootJniLocal(long objectId, int threadSerial, int frameNumber) throws IOException;
    void gcRootJavaFrame(long objectId, int threadSerial, int frameNumber) throws IOException;
    void gcRootNativeStack(long objectId, int threadSerial) throws IOException;
    void gcRootStickyClass(long objectId) throws IOException;
    void gcRootThreadBlock(long objectId, int threadSerial) throws IOException;
    void gcRootMonitorUsed(long objectId) throws IOException;
    void gcRootThreadObj(long objectId, int threadSerial, int stackSerial) throws IOException;

    // Heap objects
    void classDump(long classObjectId, long superClassId, int instanceSize,
                   long[] fieldNameIds, byte[] fieldTypes) throws IOException;
    void instanceDump(long objectId, long classObjectId, byte[] instanceData) throws IOException;
    void objArrayDump(long objectId, long elementClassId, long[] elements) throws IOException;
    void primArrayDump(long objectId, int elementType, int numElements, byte[] data) throws IOException;
}
