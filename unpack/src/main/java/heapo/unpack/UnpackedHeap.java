package heapo.unpack;

import java.nio.file.Path;

/**
 * Handle returned by {@link Unpacker#unpack} describing a fully-unpacked heap.
 * Index files live under {@code outputDir/indexes/}.
 */
public record UnpackedHeap(Path outputDir, int objectCount, int classCount) {

    public Path indexDir()   { return outputDir.resolve("indexes"); }
    public Path bitsetsDir() { return outputDir.resolve("bitsets"); }
}
