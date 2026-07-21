package com.rag.backend.agent.parse.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedTextCleanerTest {
    private final RuleBasedTextCleaner cleaner = new RuleBasedTextCleaner();

    @Test
    void removesRepeatedHeadersFootersAndNormalizesWhitespace() {
        List<String> repeated = cleaner.detectRepeatedHeadersAndFooters(List.of(
                "Course Notes\n正文 A\nConfidential",
                "Course Notes\n正文 B\nConfidential",
                "Course Notes\n正文 C\nConfidential"
        ));

        String cleaned = cleaner.clean("Course Notes\nhello-\nworld\n\n\n第 1 页\nConfidential", repeated);

        assertThat(repeated).contains("Course Notes", "Confidential");
        assertThat(cleaned).isEqualTo("helloworld");
    }
}
