package heapo.samples;

/**
 * Allocates objects that are dropped again before the heap is dumped.
 *
 * <p>A dump written with {@code live=false} does not collect first, so these objects are
 * present in the file but unreachable from any GC root — the state a
 * {@code HeapDumpOnOutOfMemoryError} dump captures.
 */
public class UnreachableObjects {

    private static final int COUNT     = 64;
    private static final int ARRAY_BYTES = 1024 * 1024;

    /** Set while allocating, cleared before the dump so the objects become unreachable. */
    private static Object[] leftover;

    public static void allocate() {
        Object[] holder = new Object[COUNT];
        for (int i = 0; i < COUNT; i++) {
            holder[i] = new byte[ARRAY_BYTES];
        }
        leftover = holder;
    }

    /**
     * Drops the last reference. The allocation and this call must be separated by a method
     * boundary so that no stack frame of the dumping thread still refers to the objects.
     */
    public static void drop() {
        leftover = null;
    }
}
