package heapo.util;

import java.util.Arrays;

/**
 * Builds a sorted, deduplicated {@code long[]} with bounded memory usage.
 *
 * Compacts (sort + deduplicate in-place) whenever the internal buffer fills,
 * growing only if the compacted result still leaves no room. This keeps peak
 * allocation at most {@code 2 × unique_count} longs regardless of how many
 * duplicate values are added.
 *
 * Call {@link #add} during the collection phase, then {@link #build} once to
 * obtain the final sorted unique array. The builder should not be used after
 * {@link #build} is called.
 */
public final class SortedLongSetBuilder {

    private long[] data;
    private int size;

    public SortedLongSetBuilder() {
        this(64);
    }

    public SortedLongSetBuilder(int initialCapacity) {
        this.data = new long[Math.max(1, initialCapacity)];
    }

    public void add(long value) {
        if (size == data.length) compact();
        data[size++] = value;
    }

    public long[] build() {
        compact();
        return Arrays.copyOf(data, size);
    }

    private void compact() {
        Arrays.sort(data, 0, size);
        int out = 0;
        for (int i = 0; i < size; i++) {
            if (out == 0 || data[i] != data[out - 1]) data[out++] = data[i];
        }
        size = out;
        if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
    }
}
