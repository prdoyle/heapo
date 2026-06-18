package heapo.unpack;

/** No-op base implementation of {@link HprofHandler}. */
public abstract class BaseHprofHandler implements HprofHandler {

    private int idSize = 4;

    @Override
    public int idSize() { return idSize; }

    @Override
    public void header(String magic, int idSize, long timestamp) {
        this.idSize = idSize;
    }

    @Override public void string(long rawId, String value) {}
    @Override public void loadClass(int classSerial, long classObjectId, long nameStringId) {}
    @Override public void heapDumpEnd() {}
    @Override public void gcRootUnknown(long objectId) {}
    @Override public void gcRootJniGlobal(long objectId, long jniGlobalRefId) {}
    @Override public void gcRootJniLocal(long objectId, int threadSerial, int frameNumber) {}
    @Override public void gcRootJavaFrame(long objectId, int threadSerial, int frameNumber) {}
    @Override public void gcRootNativeStack(long objectId, int threadSerial) {}
    @Override public void gcRootStickyClass(long objectId) {}
    @Override public void gcRootThreadBlock(long objectId, int threadSerial) {}
    @Override public void gcRootMonitorUsed(long objectId) {}
    @Override public void gcRootThreadObj(long objectId, int threadSerial, int stackSerial) {}
    @Override public void classDump(long classObjectId, long superClassId, int instanceSize,
                                    long[] fieldNameIds, byte[] fieldTypes) {}
    @Override public void instanceDump(long objectId, long classObjectId, byte[] instanceData) {}
    @Override public void objArrayDump(long objectId, long elementClassId, long[] elements) {}
    @Override public void primArrayDump(long objectId, int elementType, int numElements, byte[] data) {}
}
