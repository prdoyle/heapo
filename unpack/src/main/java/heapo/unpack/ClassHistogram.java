package heapo.unpack;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a class instance-count histogram from an HPROF file. */
public final class ClassHistogram {

    public record Entry(String className, long instanceCount) {}

    public static List<Entry> build(Path hprofPath) throws IOException {
        var handler = new HistogramHandler();
        new HprofReader(hprofPath).read(handler);
        return handler.toHistogram();
    }

    public static void print(List<Entry> histogram, PrintStream out) {
        out.printf("%10s  %s%n", "instances", "class");
        histogram.stream()
            .sorted(Comparator.comparingLong(Entry::instanceCount).reversed())
            .limit(50)
            .forEach(e -> out.printf("%10d  %s%n", e.instanceCount(), e.className()));
    }

    private static final class HistogramHandler extends BaseHprofHandler {
        /** string rawId → string value */
        private final Map<Long, String> strings = new HashMap<>();
        /** class object rawId → string rawId of name */
        private final Map<Long, Long> classNameIds = new HashMap<>();
        /** class object rawId → instance count */
        private final Map<Long, Long> counts = new HashMap<>();

        @Override
        public void string(long rawId, String value) {
            strings.put(rawId, value);
        }

        @Override
        public void loadClass(int classSerial, long classObjectId, long nameStringId) {
            classNameIds.put(classObjectId, nameStringId);
        }

        @Override
        public void instanceDump(long objectId, long classObjectId, byte[] instanceData) {
            counts.merge(classObjectId, 1L, Long::sum);
        }

        @Override
        public void objArrayDump(long objectId, long elementClassId, long[] elements) {
            counts.merge(objectId, 1L, Long::sum); // count the array itself under its class
        }

        @Override
        public void primArrayDump(long objectId, int elementType, int numElements, byte[] data) {
            // primitive arrays are tracked by type code, not a class object id
        }

        List<Entry> toHistogram() {
            var result = new ArrayList<Entry>();
            for (var entry : counts.entrySet()) {
                long classId = entry.getKey();
                long nameStringId = classNameIds.getOrDefault(classId, -1L);
                String name = strings.getOrDefault(nameStringId, "<unknown@" + classId + ">");
                // HPROF names use '/' as separator; convert to '.'
                result.add(new Entry(name.replace('/', '.'), entry.getValue()));
            }
            return result;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ClassHistogram <file.hprof>");
            System.exit(1);
        }
        var histogram = build(Path.of(args[0]));
        print(histogram, System.out);
    }
}
