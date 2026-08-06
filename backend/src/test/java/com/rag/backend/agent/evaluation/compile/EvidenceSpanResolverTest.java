package com.rag.backend.agent.evaluation.compile;

import com.rag.backend.agent.evaluation.model.EvidenceSpan;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceSpanResolverTest {

    private final EvidenceSpan ev1 = new EvidenceSpan(
            "course-a/doc-1", "sha256:S1", "sha256:P1", 1, "章节A", 10, 30, "sha256:Q1");
    private final EvidenceSpan ev2 = new EvidenceSpan(
            "course-b/doc-2", "sha256:S2", "sha256:P2", 1, "章节B", 10, 30, "sha256:Q2");

    private final Map<String, EvidenceIndexMapping> map = Map.of(
            "ev-1", new EvidenceIndexMapping("ev-1", "course-a", Set.of(101L), ev1),
            "ev-2", new EvidenceIndexMapping("ev-2", "course-b", Set.of(102L), ev2)
    );

    @Test
    void resolvesUniqueSpanForSameCourse() {
        EvidenceSpan result = EvidenceSpanResolver.resolveUnique(map, "ev-1", "course-a");
        assertEquals(ev1, result);
    }

    @Test
    void rejectsMissingEvidenceId() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceSpanResolver.resolveUnique(map, "missing", "course-a"));
        assertEquals(true, error.getMessage().contains("cannot map evidenceId"));
    }

    @Test
    void rejectsEvidenceFromAnotherCourse() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceSpanResolver.resolveUnique(map, "ev-2", "course-a"));
        assertEquals(true, error.getMessage().contains("跨课程"));
    }

    @Test
    void rejectsBlankEvidenceId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceSpanResolver.resolveUnique(map, "  ", "course-a"));
    }
}
