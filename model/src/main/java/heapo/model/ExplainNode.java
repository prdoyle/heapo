package heapo.model;

/** One node in a dominator-chain explanation (from a leaf object up to the GC root). */
public record ExplainNode(int denseId, String className, long retainedSize, int depth) {}
