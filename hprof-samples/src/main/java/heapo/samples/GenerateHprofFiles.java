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

        System.out.println("Generated HPROF files in: " + outputDir);
    }

    private static void dump(Path path) throws Exception {
        var server = ManagementFactory.getPlatformMBeanServer();
        var bean = ManagementFactory.newPlatformMXBeanProxy(
            server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(path.toAbsolutePath().toString(), true);
        System.out.println("  Wrote: " + path);
    }
}
