package heapo.model;

public record ExplainNode(int denseId, String className, long retainedSize, int depth,
                          String description, String notes, String field) {

    public ExplainNode(int denseId, String className, long retainedSize, int depth) {
        this(denseId, className, retainedSize, depth, null, null, null);
    }
}
