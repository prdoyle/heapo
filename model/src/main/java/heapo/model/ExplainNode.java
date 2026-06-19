package heapo.model;

/**
 * One node in a dominator-chain explanation (from a leaf object up to the GC root).
 *
 * <p>The {@code notes} field carries freeform human-readable context that cannot yet be expressed
 * as a structured column (e.g. "KnownObjects.class", "via fields f1, f2"). As patterns solidify,
 * individual notes items are candidates to become first-class fields in this record.
 */
public record ExplainNode(int denseId, String className, long retainedSize, int depth,
                          String notes) {

    /** Convenience constructor for nodes with no notes. */
    public ExplainNode(int denseId, String className, long retainedSize, int depth) {
        this(denseId, className, retainedSize, depth, null);
    }
}
