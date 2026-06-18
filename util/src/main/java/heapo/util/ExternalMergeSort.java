package heapo.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * External merge sort for fixed-size binary records.
 * Uses a bounded RAM buffer; never loads the full input into memory.
 */
public final class ExternalMergeSort {

    public static final int DEFAULT_MAX_RAM_BYTES = 64 * 1024 * 1024;

    private ExternalMergeSort() {}

    /**
     * Sort {@code inputFile} into {@code outputFile}.
     * Records in both files are exactly {@code recordSizeBytes} long.
     * The comparator receives read-only ByteBuffers positioned at the start of each record.
     * At most {@code maxRamBytes} of heap is used for the sort buffer.
     * Temporary run files are written to {@code tempDir} and deleted on completion.
     */
    public static void sort(
        Path inputFile,
        Path outputFile,
        Path tempDir,
        int recordSizeBytes,
        Comparator<ByteBuffer> comparator,
        int maxRamBytes
    ) throws IOException {
        List<Path> runs = createSortedRuns(inputFile, tempDir, recordSizeBytes, comparator, maxRamBytes);
        try {
            mergeRuns(runs, outputFile, recordSizeBytes, comparator);
        } finally {
            for (Path run : runs) {
                try { Files.deleteIfExists(run); } catch (IOException ignored) {}
            }
        }
    }

    // ── Phase 1: split input into sorted runs ────────────────────────────────

    private static List<Path> createSortedRuns(
        Path inputFile,
        Path tempDir,
        int recordSizeBytes,
        Comparator<ByteBuffer> comparator,
        int maxRamBytes
    ) throws IOException {
        int recordsPerRun = Math.max(1, maxRamBytes / recordSizeBytes);
        byte[] inBuf  = new byte[recordsPerRun * recordSizeBytes];
        byte[] outBuf = new byte[inBuf.length];
        List<Path> runs = new ArrayList<>();
        int runIndex = 0;

        try (var dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(inputFile), 1 << 16))) {
            while (true) {
                int totalBytes = dis.readNBytes(inBuf, 0, inBuf.length);
                int recordCount = totalBytes / recordSizeBytes;
                if (recordCount == 0) break;

                // Sort by index to avoid moving raw bytes during the sort
                Integer[] indices = new Integer[recordCount];
                for (int i = 0; i < recordCount; i++) indices[i] = i;
                Arrays.sort(indices, (a, b) -> comparator.compare(
                    ByteBuffer.wrap(inBuf, a * recordSizeBytes, recordSizeBytes).slice(),
                    ByteBuffer.wrap(inBuf, b * recordSizeBytes, recordSizeBytes).slice()
                ));

                // Copy sorted records into outBuf
                for (int i = 0; i < recordCount; i++) {
                    System.arraycopy(inBuf, indices[i] * recordSizeBytes,
                                     outBuf, i * recordSizeBytes, recordSizeBytes);
                }

                Path runPath = tempDir.resolve("ems-run-" + runIndex++ + ".bin");
                runs.add(runPath);
                try (var out = new BufferedOutputStream(
                        Files.newOutputStream(runPath, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), 1 << 16)) {
                    out.write(outBuf, 0, recordCount * recordSizeBytes);
                }
            }
        }
        return runs;
    }

    // ── Phase 2: k-way merge ─────────────────────────────────────────────────

    private static void mergeRuns(
        List<Path> runs,
        Path outputFile,
        int recordSizeBytes,
        Comparator<ByteBuffer> comparator
    ) throws IOException {
        RunReader[] readers = new RunReader[runs.size()];
        try {
            for (int i = 0; i < runs.size(); i++) {
                readers[i] = new RunReader(runs.get(i), recordSizeBytes);
            }

            // Min-heap keyed by each reader's current record
            var queue = new PriorityQueue<Integer>(Math.max(1, runs.size()),
                (a, b) -> comparator.compare(
                    ByteBuffer.wrap(readers[a].current()),
                    ByteBuffer.wrap(readers[b].current())
                )
            );
            for (int i = 0; i < readers.length; i++) {
                if (readers[i].hasNext()) queue.add(i);
            }

            try (var out = new BufferedOutputStream(
                    Files.newOutputStream(outputFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), 1 << 16)) {
                while (!queue.isEmpty()) {
                    int ri = queue.poll();
                    out.write(readers[ri].current());
                    readers[ri].advance();
                    if (readers[ri].hasNext()) queue.add(ri);
                }
            }
        } finally {
            for (RunReader r : readers) {
                if (r != null) { try { r.close(); } catch (IOException ignored) {} }
            }
        }
    }

    // ── Buffered reader for a single run file ────────────────────────────────

    private static final class RunReader {
        private final DataInputStream in;
        private final int recordSizeBytes;
        private byte[] current;
        private boolean exhausted;

        RunReader(Path path, int recordSizeBytes) throws IOException {
            this.in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 64 * 1024));
            this.recordSizeBytes = recordSizeBytes;
            advance();
        }

        boolean hasNext() { return !exhausted; }
        byte[] current() { return current; }

        void advance() throws IOException {
            if (current == null) current = new byte[recordSizeBytes];
            int n = in.readNBytes(current, 0, recordSizeBytes);
            if (n == 0) {
                exhausted = true;
                current = null;
            } else if (n != recordSizeBytes) {
                throw new IOException(
                    "Partial record at end of merge run (got " + n + " of " + recordSizeBytes + " bytes)");
            }
        }

        void close() throws IOException { in.close(); }
    }
}
