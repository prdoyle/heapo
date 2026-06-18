package heapo.unpack;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streaming HPROF binary format reader. Parses records one at a time and
 * dispatches to an {@link HprofHandler}. Never loads a full segment into memory.
 */
public final class HprofReader {

    // Top-level record tags
    public static final int TAG_STRING             = 0x01;
    public static final int TAG_LOAD_CLASS         = 0x02;
    public static final int TAG_UNLOAD_CLASS       = 0x03;
    public static final int TAG_STACK_FRAME        = 0x04;
    public static final int TAG_STACK_TRACE        = 0x05;
    public static final int TAG_ALLOC_SITES        = 0x06;
    public static final int TAG_HEAP_SUMMARY       = 0x07;
    public static final int TAG_START_THREAD       = 0x0A;
    public static final int TAG_END_THREAD         = 0x0B;
    public static final int TAG_HEAP_DUMP          = 0x0C;
    public static final int TAG_HEAP_DUMP_SEGMENT  = 0x1C;
    public static final int TAG_HEAP_DUMP_END      = 0x2C;
    public static final int TAG_CPU_SAMPLES        = 0x0D;
    public static final int TAG_CONTROL_SETTINGS   = 0x0E;

    // Heap dump sub-record tags
    public static final int HPROF_GC_ROOT_UNKNOWN       = 0xFF;
    public static final int HPROF_GC_ROOT_JNI_GLOBAL    = 0x01;
    public static final int HPROF_GC_ROOT_JNI_LOCAL     = 0x02;
    public static final int HPROF_GC_ROOT_JAVA_FRAME    = 0x03;
    public static final int HPROF_GC_ROOT_NATIVE_STACK  = 0x04;
    public static final int HPROF_GC_ROOT_STICKY_CLASS  = 0x05;
    public static final int HPROF_GC_ROOT_THREAD_BLOCK  = 0x06;
    public static final int HPROF_GC_ROOT_MONITOR_USED  = 0x07;
    public static final int HPROF_GC_ROOT_THREAD_OBJ    = 0x08;
    public static final int HPROF_GC_CLASS_DUMP         = 0x20;
    public static final int HPROF_GC_INSTANCE_DUMP      = 0x21;
    public static final int HPROF_GC_OBJ_ARRAY_DUMP     = 0x22;
    public static final int HPROF_GC_PRIM_ARRAY_DUMP    = 0x23;

    // Primitive type codes used in field descriptors and PRIM_ARRAY
    public static final int TYPE_OBJECT  = 2;
    public static final int TYPE_BOOLEAN = 4;
    public static final int TYPE_CHAR    = 5;
    public static final int TYPE_FLOAT   = 6;
    public static final int TYPE_DOUBLE  = 7;
    public static final int TYPE_BYTE    = 8;
    public static final int TYPE_SHORT   = 9;
    public static final int TYPE_INT     = 10;
    public static final int TYPE_LONG    = 11;

    private final Path hprofPath;

    public HprofReader(Path hprofPath) {
        this.hprofPath = hprofPath;
    }

    public void read(HprofHandler handler) throws IOException {
        try (var raw = new BufferedInputStream(Files.newInputStream(hprofPath), 1 << 20)) {
            var in = new CountingInputStream(raw);
            readHeader(in, handler);
            while (true) {
                int tag;
                try {
                    tag = in.readUnsignedByte();
                } catch (EOFException e) {
                    break;
                }
                int timestamp = in.readInt();
                long length = Integer.toUnsignedLong(in.readInt());

                switch (tag) {
                    case TAG_STRING -> readString(in, length, handler);
                    case TAG_LOAD_CLASS -> readLoadClass(in, handler);
                    case TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEGMENT -> readHeapDump(in, length, handler);
                    case TAG_HEAP_DUMP_END -> handler.heapDumpEnd();
                    default -> in.skipNBytes(length);
                }
            }
        }
    }

    private void readHeader(CountingInputStream in, HprofHandler handler) throws IOException {
        // Null-terminated magic string: "JAVA PROFILE 1.0.2\0"
        var sb = new StringBuilder();
        int b;
        while ((b = in.readUnsignedByte()) != 0) {
            sb.append((char) b);
        }
        int idSize = in.readInt();
        long timestamp = in.readLong();
        handler.header(sb.toString(), idSize, timestamp);
    }

    private void readString(CountingInputStream in, long length, HprofHandler handler) throws IOException {
        long rawId = readId(in, handler.idSize());
        int nameLen = (int) (length - handler.idSize());
        byte[] bytes = in.readNBytes(nameLen);
        handler.string(rawId, new String(bytes));
    }

    private void readLoadClass(CountingInputStream in, HprofHandler handler) throws IOException {
        int classSerial = in.readInt();
        long classObjectId = readId(in, handler.idSize());
        int stackSerial = in.readInt();
        long nameStringId = readId(in, handler.idSize());
        handler.loadClass(classSerial, classObjectId, nameStringId);
    }

    private void readHeapDump(CountingInputStream in, long segmentLength, HprofHandler handler) throws IOException {
        long end = in.position() + segmentLength;
        while (in.position() < end) {
            int subTag = in.readUnsignedByte();
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN      -> readRootUnknown(in, handler);
                case HPROF_GC_ROOT_JNI_GLOBAL   -> readRootJniGlobal(in, handler);
                case HPROF_GC_ROOT_JNI_LOCAL    -> readRootJniLocal(in, handler);
                case HPROF_GC_ROOT_JAVA_FRAME   -> readRootJavaFrame(in, handler);
                case HPROF_GC_ROOT_NATIVE_STACK -> readRootNativeStack(in, handler);
                case HPROF_GC_ROOT_STICKY_CLASS -> readRootStickyClass(in, handler);
                case HPROF_GC_ROOT_THREAD_BLOCK -> readRootThreadBlock(in, handler);
                case HPROF_GC_ROOT_MONITOR_USED -> readRootMonitorUsed(in, handler);
                case HPROF_GC_ROOT_THREAD_OBJ   -> readRootThreadObj(in, handler);
                case HPROF_GC_CLASS_DUMP        -> readClassDump(in, handler);
                case HPROF_GC_INSTANCE_DUMP     -> readInstanceDump(in, handler);
                case HPROF_GC_OBJ_ARRAY_DUMP    -> readObjArrayDump(in, handler);
                case HPROF_GC_PRIM_ARRAY_DUMP   -> readPrimArrayDump(in, handler);
                default -> throw new IOException(
                    String.format("Unknown heap dump sub-tag: 0x%02X at position %d", subTag, in.position()));
            }
        }
    }

    // ── GC root readers ──────────────────────────────────────────────────────

    private void readRootUnknown(CountingInputStream in, HprofHandler h) throws IOException {
        h.gcRootUnknown(readId(in, h.idSize()));
    }

    private void readRootJniGlobal(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        long jniGlobalRefId = readId(in, h.idSize());
        h.gcRootJniGlobal(objId, jniGlobalRefId);
    }

    private void readRootJniLocal(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        int threadSerial = in.readInt();
        int frameNumber = in.readInt();
        h.gcRootJniLocal(objId, threadSerial, frameNumber);
    }

    private void readRootJavaFrame(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        int threadSerial = in.readInt();
        int frameNumber = in.readInt();
        h.gcRootJavaFrame(objId, threadSerial, frameNumber);
    }

    private void readRootNativeStack(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        int threadSerial = in.readInt();
        h.gcRootNativeStack(objId, threadSerial);
    }

    private void readRootStickyClass(CountingInputStream in, HprofHandler h) throws IOException {
        h.gcRootStickyClass(readId(in, h.idSize()));
    }

    private void readRootThreadBlock(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        int threadSerial = in.readInt();
        h.gcRootThreadBlock(objId, threadSerial);
    }

    private void readRootMonitorUsed(CountingInputStream in, HprofHandler h) throws IOException {
        h.gcRootMonitorUsed(readId(in, h.idSize()));
    }

    private void readRootThreadObj(CountingInputStream in, HprofHandler h) throws IOException {
        long objId = readId(in, h.idSize());
        int threadSerial = in.readInt();
        int stackSerial = in.readInt();
        h.gcRootThreadObj(objId, threadSerial, stackSerial);
    }

    // ── Heap dump object readers ─────────────────────────────────────────────

    private void readClassDump(CountingInputStream in, HprofHandler h) throws IOException {
        int idSize = h.idSize();
        long classObjectId = readId(in, idSize);
        int stackSerial = in.readInt();
        long superClassId = readId(in, idSize);
        long classLoaderObjectId = readId(in, idSize);
        long signersObjectId = readId(in, idSize);
        long protectionDomainId = readId(in, idSize);
        readId(in, idSize); // reserved1
        readId(in, idSize); // reserved2
        int instanceSize = in.readInt();

        // constant pool (skip)
        int cpCount = in.readUnsignedShort();
        for (int i = 0; i < cpCount; i++) {
            in.readUnsignedShort(); // cp index
            int type = in.readUnsignedByte();
            in.skipNBytes(typeSize(type, idSize));
        }

        // static fields — buffer object refs; fired after classDump so handler has the dense ID
        int staticCount = in.readUnsignedShort();
        long[] staticObjectRefs = null;
        int staticObjectCount = 0;
        for (int i = 0; i < staticCount; i++) {
            readId(in, idSize); // name string id
            int type = in.readUnsignedByte();
            if (type == TYPE_OBJECT) {
                long ref = readId(in, idSize);
                if (ref != 0) {
                    if (staticObjectRefs == null) staticObjectRefs = new long[staticCount];
                    staticObjectRefs[staticObjectCount++] = ref;
                }
            } else {
                in.skipNBytes(typeSize(type, idSize));
            }
        }

        // instance field descriptors
        int instanceFieldCount = in.readUnsignedShort();
        long[] fieldNameIds = new long[instanceFieldCount];
        byte[] fieldTypes = new byte[instanceFieldCount];
        for (int i = 0; i < instanceFieldCount; i++) {
            fieldNameIds[i] = readId(in, idSize);
            fieldTypes[i] = (byte) in.readUnsignedByte();
        }

        h.classDump(classObjectId, superClassId, instanceSize, fieldNameIds, fieldTypes);
        // Fire static object refs after classDump so handlers have the dense ID available
        if (staticObjectRefs != null) {
            for (int i = 0; i < staticObjectCount; i++) {
                h.staticObjectField(classObjectId, staticObjectRefs[i]);
            }
        }
    }

    private void readInstanceDump(CountingInputStream in, HprofHandler h) throws IOException {
        int idSize = h.idSize();
        long objectId = readId(in, idSize);
        int stackSerial = in.readInt();
        long classObjectId = readId(in, idSize);
        int dataLen = in.readInt();
        byte[] data = in.readNBytes(dataLen);
        h.instanceDump(objectId, classObjectId, data);
    }

    private void readObjArrayDump(CountingInputStream in, HprofHandler h) throws IOException {
        int idSize = h.idSize();
        long objectId = readId(in, idSize);
        int stackSerial = in.readInt();
        int numElements = in.readInt();
        long elementClassId = readId(in, idSize);
        long[] elements = new long[numElements];
        for (int i = 0; i < numElements; i++) {
            elements[i] = readId(in, idSize);
        }
        h.objArrayDump(objectId, elementClassId, elements);
    }

    private void readPrimArrayDump(CountingInputStream in, HprofHandler h) throws IOException {
        int idSize = h.idSize();
        long objectId = readId(in, idSize);
        int stackSerial = in.readInt();
        int numElements = in.readInt();
        int elementType = in.readUnsignedByte();
        int elemSize = primitiveTypeSize(elementType);
        byte[] data = in.readNBytes((long) numElements * elemSize);
        h.primArrayDump(objectId, elementType, numElements, data);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    static long readId(CountingInputStream in, int idSize) throws IOException {
        return switch (idSize) {
            case 4 -> Integer.toUnsignedLong(in.readInt());
            case 8 -> in.readLong();
            default -> throw new IOException("Unsupported id size: " + idSize);
        };
    }

    static int typeSize(int type, int idSize) {
        return switch (type) {
            case TYPE_OBJECT  -> idSize;
            case TYPE_BOOLEAN, TYPE_BYTE  -> 1;
            case TYPE_CHAR, TYPE_SHORT    -> 2;
            case TYPE_FLOAT, TYPE_INT     -> 4;
            case TYPE_DOUBLE, TYPE_LONG   -> 8;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    static int primitiveTypeSize(int type) {
        return switch (type) {
            case TYPE_BOOLEAN, TYPE_BYTE  -> 1;
            case TYPE_CHAR, TYPE_SHORT    -> 2;
            case TYPE_FLOAT, TYPE_INT     -> 4;
            case TYPE_DOUBLE, TYPE_LONG   -> 8;
            default -> throw new IllegalArgumentException("Unknown primitive type: " + type);
        };
    }
}
