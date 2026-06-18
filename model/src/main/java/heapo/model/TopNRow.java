package heapo.model;

/** A single row in a TOP-N query result. */
public record TopNRow(int rank, int denseId, String className,
                      long retainedSize, long shallowSize) {}
