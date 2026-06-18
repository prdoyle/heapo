package heapo.model;

/** Summary information about a class found in the heap. */
public record ClassInfo(int denseId, String className, long instanceCount) {}
