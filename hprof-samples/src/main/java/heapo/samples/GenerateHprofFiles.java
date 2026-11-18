package heapo.samples;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

public class GenerateHprofFiles {

    public static void main(String[] args) throws Exception {
        String outputDirProp = System.getProperty("output.dir");
        if (outputDirProp == null) {
            throw new IllegalStateException("System property 'output.dir' must be set");
        }
        Path outputDir = Path.of(outputDirProp);
        Files.createDirectories(outputDir);

        KnownObjects.allocate();
        dump(outputDir.resolve("known-objects.hprof"));

        DeepChain.allocate();
        dump(outputDir.resolve("deep-chain.hprof"));

        // Must come last: the dump relies on nothing collecting these objects between
        // drop() and dumpHeap(..., live=false).
        UnreachableObjects.allocate();
        UnreachableObjects.drop();
        dump(outputDir.resolve("unreachable-objects.hprof"), false);

        System.out.println("Generated HPROF files in: " + outputDir);
    }

    private static void dump(Path path) throws Exception {
        dump(path, true);
    }

    private static void dump(Path path, boolean live) throws Exception {
        Files.deleteIfExists(path);
        var server = ManagementFactory.getPlatformMBeanServer();
        var bean = ManagementFactory.newPlatformMXBeanProxy(
            server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(path.toAbsolutePath().toString(), live);
        System.out.println("  Wrote: " + path);
    }
}
