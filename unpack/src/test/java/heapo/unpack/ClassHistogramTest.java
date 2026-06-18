package heapo.unpack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ClassHistogramTest {

    static Path samplesDir = Path.of(System.getProperty("hprof.samples.dir",
        "build/hprof-samples"));
    static List<ClassHistogram.Entry> histogram;

    @BeforeAll
    static void setup() throws Exception {
        histogram = ClassHistogram.build(samplesDir.resolve("known-objects.hprof"));
    }

    @Test
    void containsFooInstances() {
        Map<String, Long> counts = histogram.stream()
            .collect(Collectors.toMap(ClassHistogram.Entry::className,
                                      ClassHistogram.Entry::instanceCount));

        assertTrue(counts.containsKey("heapo.samples.KnownObjects$Foo"),
            "histogram should contain KnownObjects$Foo; got: " + counts.keySet().stream()
                .filter(k -> k.contains("KnownObjects")).toList());

        assertTrue(counts.get("heapo.samples.KnownObjects$Foo") >= 1,
            "expected at least 1 Foo instance");
    }

    @Test
    void containsBarInstances() {
        Map<String, Long> counts = histogram.stream()
            .collect(Collectors.toMap(ClassHistogram.Entry::className,
                                      ClassHistogram.Entry::instanceCount));

        assertTrue(counts.containsKey("heapo.samples.KnownObjects$Bar"),
            "histogram should contain KnownObjects$Bar");
        assertTrue(counts.get("heapo.samples.KnownObjects$Bar") >= 1,
            "expected at least 1 Bar instance");
    }

    @Test
    void histogramIsNonEmpty() {
        assertFalse(histogram.isEmpty(), "histogram should not be empty");
    }
}
