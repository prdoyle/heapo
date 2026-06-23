package heapo.model;

/**
 * One row in an INSPECT result.
 * Exactly one of: non-null object ref (id >= 0), null object ref (isNullRef true),
 * or primitive (primType != null).
 */
public record FieldRow(
    String fieldName,     // "fieldName" or "[n]" for array elements
    int refDenseId,       // >= 0 for non-null object refs; -1 otherwise
    boolean isNullRef,    // true for null object reference fields
    String className,     // runtime class name for non-null object refs; null otherwise
    long retainedSize,    // for non-null object refs only; -1 otherwise
    long shallowSize,     // for non-null object refs only; -1 otherwise
    String description,   // enriched description (String content, class name, etc.); null if none
    String primType,      // "int", "long", etc. for primitive fields; null for object fields
    long primValue        // valid when primType != null
) {
    public boolean isObject()    { return primType == null && !isNullRef && refDenseId >= 0; }
    public boolean isPrimitive() { return primType != null; }
}
