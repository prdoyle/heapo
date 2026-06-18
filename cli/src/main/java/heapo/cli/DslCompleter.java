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
        "ALL", "FROM", "CLASSES", "EXPLAIN", "RETAINED", "STATUS",
        "NAMES", "UNDO", "HISTORY", "CALL", "FORGET",
        "SELECT", "WITH", "exit", "quit"
    );

    private static final List<String> PIPELINE_FILTERS = List.of("IN", "NOT", "RETAINED", "RETAINING");
    private static final List<String> BUILTIN_NAMES = List.of(
        "GcRoots", "Threads", "ClassLoaders",
        "SoftReferences", "WeakReferences", "PhantomReferences");
    private static final List<String> PIPELINE_TERMINALS =
        List.of("TOP", "BOTTOM", "AGGREGATE");

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
            case "ALL"  -> completeAll(words, wordIndex, partial, candidates);
            case "FROM" -> completeFrom(words, wordIndex, partial, candidates);
            case "CLASSES", "NAMES" -> {
                if (wordIndex == 1) suggest(candidates, partial, List.of("MATCHING"));
            }
            case "EXPLAIN" -> {
                if (wordIndex == 1) suggest(candidates, partial, List.of("#"));
            }
            case "RETAINED" -> completeRetainedBy(words, wordIndex, partial, candidates);
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
                List.of("TOP", "BOTTOM", "RETAINING", "AGGREGATE", "IN", "NOT", "RETAINED"));
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

    private List<String> allNameSuggestions() {
        var list = new ArrayList<>(BUILTIN_NAMES);
        list.addAll(names.all().keySet());
        return list;
    }

    private void completeFrom(List<String> words, int idx, String partial, List<Candidate> candidates) {
        if (idx == 1) {
            // Source: THAT, built-in names, or user-defined names
            var opts = new ArrayList<>(List.of("THAT"));
            opts.addAll(allNameSuggestions());
            suggest(candidates, partial, opts);
            return;
        }
        // After source: filters or terminal
        completePipelineSuffix(words, idx, partial, candidates);
    }

    private void completePipelineSuffix(List<String> words, int idx, String partial,
                                         List<Candidate> candidates) {
        // Skip the source tokens: for ALL skip keyword + class name, for FROM skip keyword + source name
        int i = 2; // both ALL and FROM have 2-token sources

        while (i < idx) {
            String w = words.get(i).toUpperCase();
            if (w.equals("IN")) { i += 2; continue; }
            if (w.equals("NOT") && i + 1 < words.size() && words.get(i + 1).equalsIgnoreCase("IN")) {
                i += 3; continue;
            }
            if (w.equals("RETAINED") && i + 1 < words.size()
                    && words.get(i + 1).equalsIgnoreCase("BY")) {
                i += 3; continue; // RETAINED BY <name>
            }
            if (w.equals("RETAINING") && i + 2 < words.size()) {
                i += 3; continue; // RETAINING op n
            }
            // Must be a terminal keyword — don't suggest more
            return;
        }
        if (i == idx) {
            // At filter/terminal position: offer both
            var opts = new ArrayList<>(PIPELINE_FILTERS);
            opts.addAll(PIPELINE_TERMINALS);
            suggest(candidates, partial, opts);
        } else if (i == idx - 1) {
            // One token into an incomplete filter — figure out which
            String prev = words.get(i - 1).toUpperCase();
            switch (prev) {
                case "IN"       -> suggest(candidates, partial, allNameSuggestions());
                case "NOT"      -> suggest(candidates, partial, List.of("IN"));
                case "RETAINED" -> suggest(candidates, partial, List.of("BY"));
                case "BY"       -> suggest(candidates, partial, allNameSuggestions());
            }
        }
    }

    private void completeRetainedBy(List<String> words, int idx, String partial, List<Candidate> candidates) {
        switch (idx) {
            case 1 -> suggest(candidates, partial, List.of("BY"));
            case 2 -> {
                if (words.get(1).equalsIgnoreCase("BY"))
                    suggest(candidates, partial, List.of("#"));
            }
            case 3 -> suggest(candidates, partial, List.of("TOP"));
            case 5 -> suggest(candidates, partial, List.of("BY"));
            case 6 -> suggest(candidates, partial, List.of("retainedSize"));
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
