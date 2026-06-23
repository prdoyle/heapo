package heapo.unpack;

import java.io.IOException;

/** No-op base implementation of {@link HprofHandler}. */
public abstract class BaseHprofHandler implements HprofHandler {

    private int idSize = 4;

    @Override
    public int idSize() { return idSize; }

    @Override public void header(String magic, int idSize, long timestamp) throws IOException { this.idSize = idSize; }
    @Override public void string(long rawId, String value) throws IOException {}
    @Override public void loadClass(int classSerial, long classObjectId, long nameStringId) throws IOException {}
    @Override public void heapDumpEnd() throws IOException {}
    @Override public void gcRootUnknown(long objectId) throws IOException {}
    @Override public void gcRootJniGlobal(long objectId, long jniGlobalRefId) throws IOException {}
    @Override public void gcRootJniLocal(long objectId, int threadSerial, int frameNumber) throws IOException {}
    @Override public void gcRootJavaFrame(long objectId, int threadSerial, int frameNumber) throws IOException {}
    @Override public void gcRootNativeStack(long objectId, int threadSerial) throws IOException {}
    @Override public void gcRootStickyClass(long objectId) throws IOException {}
    @Override public void gcRootThreadBlock(long objectId, int threadSerial) throws IOException {}
    @Override public void gcRootMonitorUsed(long objectId) throws IOException {}
    @Override public void gcRootThreadObj(long objectId, int threadSerial, int stackSerial) throws IOException {}
    @Override public void classDump(long classObjectId, long superClassId, int instanceSize,
                                    long[] fieldNameIds, byte[] fieldTypes) throws IOException {}
    @Override public void staticObjectField(long classObjectId, long nameStringId, long valueRawId) throws IOException {}
    @Override public void instanceDump(long objectId, long classObjectId, byte[] instanceData) throws IOException {}
    @Override public void objArrayDump(long objectId, long elementClassId, long[] elements) throws IOException {}
    @Override public void primArrayDump(long objectId, int elementType, int numElements, byte[] data) throws IOException {}
}
