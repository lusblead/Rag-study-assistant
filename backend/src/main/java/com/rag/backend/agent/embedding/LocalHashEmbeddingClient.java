package com.rag.backend.agent.embedding;

import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LocalHashEmbeddingClient implements EmbeddingClient {
    private final int dimension;

    public LocalHashEmbeddingClient(@Value("${milvus.embedding-dimension:1536}") int dimension) {
        this.dimension = dimension;
    }

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[dimension];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^\\p{IsHan}a-z0-9]+");

        int tokenCount = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            addToken(vector, token);
            tokenCount++;
        }
        if (tokenCount == 0) {
            addToken(vector, normalized.isBlank() ? "empty" : normalized);
        }

        normalize(vector);
        List<Double> result = new ArrayList<>(dimension);
        for (double value : vector) {
            result.add(value);
        }
        return result;
    }

    private void addToken(double[] vector, String token) {
        byte[] digest = sha256(token);
        int rawIndex = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
        int index = rawIndex % dimension;
        double sign = (digest[2] & 1) == 0 ? 1.0 : -1.0;
        vector[index] += sign;
    }

    private void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) {
            vector[0] = 1.0;
            return;
        }
        double scale = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / scale;
        }
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
