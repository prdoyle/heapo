package heapo.cli;

import heapo.query_engine.ClassNameIndex;
import heapo.session.NamesManager;
import heapo.unpack.UnpackedHeap;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.IOException;
import java.util.*;

/**
 * JLine3 {@link Completer} that suggests DSL tokens based on grammar position.
 */
final class DslCompleter implements Completer {

    private static final List<String> TOP_LEVEL = List.of(
        "ALL", "CLASSES", "EXPLAIN", "DOMINATOR", "STATUS",
        "NAMES", "UNDO", "HISTORY", "CALL", "FORGET",
        "SELECT", "WITH", "exit", "quit"
    );

    private final UnpackedHeap  heap;
    private final NamesManager  names;

    DslCompleter(UnpackedHeap heap, NamesManager names) {
        this.heap  = heap;
        this.names = names;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        // words() includes the current (possibly-empty) word being typed
        int wordIndex = line.wordIndex();
        String partial = wordIndex < words.size() ? words.get(wordIndex) : "";

        if (wordIndex == 0) {
            suggest(candidates, partial, TOP_LEVEL);
            return;
        }

        String w0 = words.get(0).toUpperCase();
        switch (w0) {
            case "ALL" -> completeAll(words, wordIndex, partial, candidates);
            case "CLASSES" -> {
                if (wordIndex == 1) suggest(candidates, partial, List.of("MATCHING"));
            }
            case "EXPLAIN" -> {
                if (wordIndex == 1) suggest(candidates, partial, List.of("#"));
            }
            case "DOMINATOR" -> completeDominator(words, wordIndex, partial, candidates);
            case "CALL" -> completeCall(words, wordIndex, partial, candidates);
            case "FORGET" -> {
                if (wordIndex == 1)
                    suggest(candidates, partial, new ArrayList<>(names.all().keySet()));
            }
            default -> {} // no suggestion
        }
    }

    private void completeAll(List<String> words, int idx, String partial, List<Candidate> candidates) {
        switch (idx) {
            case 1 -> {
                // class name
                try {
                    suggest(candidates, partial, ClassNameIndex.load(heap).allDottedNames());
                } catch (IOException ignored) {}
                suggest(candidates, partial, List.of("*"));
            }
            case 2 -> suggest(candidates, partial,
                List.of("TOP", "BOTTOM", "RETAINING", "AGGREGATE"));
            case 3 -> {
                String w2 = words.get(2).toUpperCase();
                if (w2.equals("AGGREGATE"))
                    suggest(candidates, partial, List.of("COUNT", "MAX", "SUM"));
            }
            case 4 -> {
                String w2 = words.get(2).toUpperCase();
                if (w2.equals("TOP") || w2.equals("BOTTOM"))
                    suggest(candidates, partial, List.of("BY"));
                else if (w2.equals("AGGREGATE"))
                    suggest(candidates, partial, List.of("retainedSize"));
            }
            case 5 -> {
                String w2 = words.get(2).toUpperCase();
                if ((w2.equals("TOP") || w2.equals("BOTTOM"))
                        && words.get(4).equalsIgnoreCase("BY"))
                    suggest(candidates, partial, List.of("retainedSize"));
            }
        }
    }

    private void completeDominator(List<String> words, int idx, String partial, List<Candidate> candidates) {
        switch (idx) {
            case 1 -> suggest(candidates, partial, List.of("SUBTREE"));
            case 2 -> {
                if (words.get(1).equalsIgnoreCase("SUBTREE"))
                    suggest(candidates, partial, List.of("OF"));
            }
            case 4 -> suggest(candidates, partial, List.of("TOP"));
            case 6 -> suggest(candidates, partial, List.of("BY"));
            case 7 -> suggest(candidates, partial, List.of("retainedSize"));
        }
    }

    private void completeCall(List<String> words, int idx, String partial, List<Candidate> candidates) {
        if (idx == 1) {
            suggest(candidates, partial, List.of("THAT"));
        } else if (idx == 2 && words.get(1).equalsIgnoreCase("THAT")) {
            suggest(candidates, partial, List.of("<name>"));
        }
    }

    private static void suggest(List<Candidate> candidates, String partial, Collection<String> options) {
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(partial.toLowerCase()))
                candidates.add(new Candidate(opt));
        }
    }
}
