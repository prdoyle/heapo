package heapo.query_engine;

import heapo.unpack.UnpackedHeap;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Maps between HPROF class names and dense class IDs. */
public final class ClassNameIndex {

    private final Map<String, Integer> slashedNameToDenseId = new HashMap<>();
    private final Map<Integer, String>  denseIdToDottedName  = new HashMap<>();

    private ClassNameIndex() {}

    public static ClassNameIndex load(UnpackedHeap heap) throws IOException {
        var index = new ClassNameIndex();
        Path path = heap.outputDir().resolve("class-names.txt");
        for (String line : Files.readAllLines(path)) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            int    denseId     = Integer.parseInt(line.substring(0, tab).strip());
            String slashedName = line.substring(tab + 1).strip();
            index.slashedNameToDenseId.put(slashedName, denseId);
            index.denseIdToDottedName.put(denseId, slashedName.replace('/', '.'));
        }
        return index;
    }

    /**
     * Returns the dense class ID for the given class name, or -1 if not found.
     * Accepts both dotted ({@code com.example.Foo}) and slashed ({@code com/example/Foo}) forms.
     */
    public int resolve(String className) {
        return slashedNameToDenseId.getOrDefault(className.replace('.', '/'), -1);
    }

    /** Returns the dotted class name for the given dense class ID, or {@code "?"} if unknown. */
    public String nameOf(int classDenseId) {
        return denseIdToDottedName.getOrDefault(classDenseId, "?");
    }

    /** All class names in slashed form. */
    public Set<String> allSlashedNames() {
        return Collections.unmodifiableSet(slashedNameToDenseId.keySet());
    }

    /** All class names in dotted form. */
    public Collection<String> allDottedNames() {
        return Collections.unmodifiableCollection(denseIdToDottedName.values());
    }

    /** All dense class IDs (== the class object's dense ID in the object array). */
    public Set<Integer> allClassDenseIds() {
        return Collections.unmodifiableSet(denseIdToDottedName.keySet());
    }
}
