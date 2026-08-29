package com.rag.backend.ingestionlab.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkProfileTest {

    @Test
    void acceptsOverlapAtBothValidBoundaries() {
        assertAll(
                () -> assertDoesNotThrow(() -> new ChunkProfile(800, 0)),
                () -> assertDoesNotThrow(() -> new ChunkProfile(800, 799)));
    }

    @Test
    void rejectsNonPositiveSizeAndOverlapOutsideWindow() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ChunkProfile(0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ChunkProfile(-1, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ChunkProfile(800, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ChunkProfile(800, 800)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ChunkProfile(800, 801)));
    }
}
