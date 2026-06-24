package heapo.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class SortedLongSetBuilderTest {

    // ── Basic correctness ─────────────────────────────────────────────────────

    @Test
    void emptyBuildReturnsEmptyArray() {
        assertArrayEquals(new long[0], new SortedLongSetBuilder().build());
    }

    @Test
    void singleElement() {
        var b = new SortedLongSetBuilder();
        b.add(42L);
        assertArrayEquals(new long[]{42L}, b.build());
    }

    @Test
    void sortedUniqueOutput() {
        var b = new SortedLongSetBuilder();
        b.add(3); b.add(1); b.add(2);
        assertArrayEquals(new long[]{1, 2, 3}, b.build());
    }

    @Test
    void duplicatesRemoved() {
        var b = new SortedLongSetBuilder();
        b.add(5); b.add(5); b.add(5);
        assertArrayEquals(new long[]{5}, b.build());
    }

    @Test
    void allDuplicates() {
        var b = new SortedLongSetBuilder(2);
        for (int i = 0; i < 100; i++) b.add(7);
        assertArrayEquals(new long[]{7}, b.build());
    }

    // ── Edge values ───────────────────────────────────────────────────────────

    @Test
    void handlesLongMinAndMax() {
        var b = new SortedLongSetBuilder();
        b.add(Long.MAX_VALUE);
        b.add(Long.MIN_VALUE);
        b.add(0);
        assertArrayEquals(new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE}, b.build());
    }

    @Test
    void handlesNegativeValues() {
        var b = new SortedLongSetBuilder();
        b.add(-1); b.add(-3); b.add(-2); b.add(-1);
        assertArrayEquals(new long[]{-3, -2, -1}, b.build());
    }

    // ── Compact-on-fill behaviour ─────────────────────────────────────────────

    @Test
    void compactOnFillWithAllDuplicatesNeverGrows() {
        // Initial capacity 4; fill with duplicates repeatedly — should never need to grow
        var b = new SortedLongSetBuilder(4);
        for (int i = 0; i < 1000; i++) b.add(1);
        assertArrayEquals(new long[]{1}, b.build());
    }

    @Test
    void compactOnFillWithUniqueValuesGrowsCorrectly() {
        // Initial capacity 4; add 16 unique values — must grow several times
        var b = new SortedLongSetBuilder(4);
        for (long i = 15; i >= 0; i--) b.add(i);
        long[] expected = LongStream.range(0, 16).toArray();
        assertArrayEquals(expected, b.build());
    }

    @Test
    void compactTriggeredExactlyAtCapacityBoundary() {
        // Fill to exactly capacity, then add one more that is a duplicate — no growth needed
        var b = new SortedLongSetBuilder(4);
        b.add(10); b.add(20); b.add(30); b.add(40); // fills capacity=4
        b.add(10); // triggers compact; 10 is a dup so size stays 4, but there's now room
        b.add(50); // should fit without another compact
        assertArrayEquals(new long[]{10, 20, 30, 40, 50}, b.build());
    }

    // ── Large input ───────────────────────────────────────────────────────────

    @Test
    void largeInputMixedDuplicates() {
        int n = 100_000;
        var b = new SortedLongSetBuilder();
        // Add 0..n-1 twice in reverse order
        for (long i = n - 1; i >= 0; i--) { b.add(i); b.add(i); }
        long[] result = b.build();
        assertEquals(n, result.length);
        assertTrue(Arrays.equals(result, LongStream.range(0, n).toArray()));
    }

    @Test
    void largeInputAllUnique() {
        int n = 50_000;
        var b = new SortedLongSetBuilder();
        for (long i = n - 1; i >= 0; i--) b.add(i);
        long[] result = b.build();
        assertEquals(n, result.length);
        assertEquals(0L, result[0]);
        assertEquals(n - 1, result[n - 1]);
    }

    // ── Idempotent build ──────────────────────────────────────────────────────

    @Test
    void buildIsStableWhenCalledOnAlreadySortedUniqueInput() {
        var b = new SortedLongSetBuilder();
        b.add(1); b.add(2); b.add(3);
        long[] first  = b.build();
        // build() compact()s internally; calling it again on a fresh builder with same input is stable
        var b2 = new SortedLongSetBuilder();
        b2.add(1); b2.add(2); b2.add(3);
        assertArrayEquals(first, b2.build());
    }
}
