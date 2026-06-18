package heapo.model;

public record VoidAnswer() implements Answer {
    public static final VoidAnswer INSTANCE = new VoidAnswer();
}
