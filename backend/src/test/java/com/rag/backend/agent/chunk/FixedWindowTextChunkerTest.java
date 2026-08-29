package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedWindowTextChunkerTest {
    private final FixedWindowTextChunker chunker =
            new FixedWindowTextChunker(new TextCleaner());
    private final ParsedDocument document =
            new ParsedDocument("title", "abcdefghij", List.of());

    @Test
    void producesDeterministicOverlappingWindows() {
        var chunks = chunker.chunk(document, 4, 1);

        assertEquals(List.of("abcd", "defg", "ghij"), chunks.stream()
                .map(chunk -> chunk.content())
                .toList());
        assertEquals(List.of(0, 1, 2), chunks.stream()
                .map(chunk -> chunk.index())
                .toList());
    }

    @Test
    void rejectsProfilesThatCannotAdvanceTheWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> chunker.chunk(document, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> chunker.chunk(document, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> chunker.chunk(document, 4, -1));
        assertThrows(IllegalArgumentException.class,
                () -> chunker.chunk(document, 4, 4));
        assertThrows(IllegalArgumentException.class,
                () -> chunker.chunk(document, 4, 5));
    }
}
