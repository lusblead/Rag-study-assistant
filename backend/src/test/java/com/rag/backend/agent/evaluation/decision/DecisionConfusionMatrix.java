package com.rag.backend.agent.evaluation.decision;

import java.util.ArrayList;
import java.util.List;

/** Explicit 3x3 matrix: truth labels are rows and predicted labels are columns. */
public record DecisionConfusionMatrix(
        String rowAxis,
        List<DecisionOutcome> truthRowOrder,
        String columnAxis,
        List<DecisionOutcome> predictionColumnOrder,
        List<List<Integer>> counts
) {
    public DecisionConfusionMatrix {
        truthRowOrder = List.copyOf(truthRowOrder);
        predictionColumnOrder = List.copyOf(predictionColumnOrder);
        List<List<Integer>> copied = new ArrayList<>();
        counts.forEach(row -> copied.add(List.copyOf(row)));
        counts = List.copyOf(copied);
        if (truthRowOrder.size() != DecisionOutcome.values().length
                || predictionColumnOrder.size() != DecisionOutcome.values().length
                || counts.size() != DecisionOutcome.values().length
                || counts.stream().anyMatch(row -> row.size() != DecisionOutcome.values().length)) {
            throw new IllegalArgumentException("decision confusion matrix must be 3x3");
        }
    }

    public int count(DecisionOutcome truth, DecisionOutcome prediction) {
        int row = truthRowOrder.indexOf(truth);
        int column = predictionColumnOrder.indexOf(prediction);
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("unknown decision label");
        }
        return counts.get(row).get(column);
    }
}
