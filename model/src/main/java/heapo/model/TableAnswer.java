package heapo.model;

public record TableAnswer(String sqlTableName, int rowCount) implements Answer {
}
