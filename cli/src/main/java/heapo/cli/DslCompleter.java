package heapo.cli;

import heapo.query_engine.ClassNameIndex;
import heapo.query_engine.DslParser;
import heapo.session.NamesManager;
import heapo.unpack.UnpackedHeap;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.IOException;
import java.util.List;

/**
 * JLine3 {@link Completer} backed by the DSL parser.
 *
 * <p>Calls {@link DslParser#parse} on the tokens before the cursor, then offers
 * the keyword completions returned by the parse result. Completion markers
 * ({@link DslParser#COMPLETE_CLASS}, {@link DslParser#COMPLETE_BITSET}) are
 * expanded against live heap / session data.
 */
final class DslCompleter implements Completer {

    private static final List<String> BUILTIN_NAMES = List.of(
        "GcRoots", "Threads", "ClassLoaders",
        "SoftReferences", "WeakReferences", "PhantomReferences"
    );

    private final UnpackedHeap heap;
    private final NamesManager names;

    DslCompleter(UnpackedHeap heap, NamesManager names) {
        this.heap  = heap;
        this.names = names;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        int wordIndex = line.wordIndex();
        String partial = wordIndex < words.size() ? words.get(wordIndex) : "";

        // Join all complete tokens (before the current partial word) and parse them.
        String completeInput = wordIndex == 0 ? ""
            : String.join(" ", words.subList(0, wordIndex));

        List<String> completions = switch (DslParser.parse(completeInput)) {
            case DslParser.Complete c  -> c.completions();
            case DslParser.Incomplete i -> i.completions();
            case DslParser.Invalid ignored -> List.of();
        };

        for (String c : completions) {
            switch (c) {
                case DslParser.COMPLETE_CLASS -> {
                    try {
                        for (String name : ClassNameIndex.load(heap).allDottedNames())
                            if (matchesPartial(name, partial))
                                candidates.add(new Candidate(name));
                    } catch (IOException ignored) {}
                    if (matchesPartial("*", partial)) candidates.add(new Candidate("*"));
                }
                case DslParser.COMPLETE_BITSET -> {
                    for (String name : BUILTIN_NAMES)
                        if (matchesPartial(name, partial)) candidates.add(new Candidate(name));
                    for (String name : names.all().keySet())
                        if (matchesPartial(name, partial)) candidates.add(new Candidate(name));
                }
                // Free-form tokens: no suggestions to add
                case DslParser.COMPLETE_NEW_NAME, DslParser.COMPLETE_IDENT,
                     "<n>", "<bytes>", "<glob>", "<value>", "i<n>", "h<n>" -> {}
                default -> {
                    if (matchesPartial(c, partial)) candidates.add(new Candidate(c));
                }
            }
        }
    }

    private static boolean matchesPartial(String candidate, String partial) {
        return candidate.toLowerCase().startsWith(partial.toLowerCase());
    }
}
