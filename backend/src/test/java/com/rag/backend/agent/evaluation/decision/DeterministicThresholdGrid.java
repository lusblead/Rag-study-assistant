package com.rag.backend.agent.evaluation.decision;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Builds stable score-boundary midpoints from the frozen snapshots exactly once. */
public final class DeterministicThresholdGrid {

    public List<Double> fromSnapshots(Collection<FrozenDecisionSnapshot> snapshots) {
        TreeSet<Double> scores = new TreeSet<>();
        snapshots.forEach(snapshot -> snapshot.candidates().forEach(candidate -> {
            if (!Double.isFinite(candidate.decisionScore())) {
                throw new IllegalArgumentException("threshold grid requires finite scores");
            }
            scores.add(candidate.decisionScore());
        }));
        if (scores.isEmpty()) {
            throw new IllegalArgumentException(
                    "threshold sweep needs at least one frozen decision score");
        }

        List<Double> unique = List.copyOf(scores);
        List<Double> thresholds = new ArrayList<>();
        double lowerSentinel = Math.nextDown(unique.get(0));
        if (!Double.isFinite(lowerSentinel)) {
            throw new IllegalArgumentException("minimum score is too small for a finite sentinel");
        }
        thresholds.add(lowerSentinel);
        for (int index = 0; index < unique.size() - 1; index++) {
            double lower = unique.get(index);
            double upper = unique.get(index + 1);
            double midpoint = lower + (upper - lower) / 2.0;
            if (!Double.isFinite(midpoint) || midpoint <= lower || midpoint >= upper) {
                midpoint = Math.nextUp(lower);
            }
            if (!Double.isFinite(midpoint) || midpoint <= lower || midpoint > upper) {
                throw new IllegalArgumentException(
                        "adjacent scores cannot produce a finite deterministic boundary");
            }
            thresholds.add(midpoint);
        }
        double upperSentinel = Math.nextUp(unique.get(unique.size() - 1));
        if (!Double.isFinite(upperSentinel)) {
            throw new IllegalArgumentException("maximum score is too large for a finite sentinel");
        }
        thresholds.add(upperSentinel);
        return List.copyOf(thresholds);
    }
}
