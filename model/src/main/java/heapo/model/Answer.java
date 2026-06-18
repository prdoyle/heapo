package heapo.model;

public sealed interface Answer permits BitSetAnswer, TableAnswer, ScalarAnswer, VoidAnswer {
}
