package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.vault.embedding.EmbeddingBackend;
import com.jarvis.knowledge.vault.index.PolishTextAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic test-only embedding backends. {@link Hashing} maps character trigrams of the
 * folded text into a fixed number of buckets: texts sharing word fragments get similar vectors.
 * It is NOT a semantic model; it only exercises the vector code paths (storage, dimension and
 * model guards, fusion, fallback) without a running provider.
 */
final class TestEmbeddingBackends {

    private TestEmbeddingBackends() {
    }

    static final class Hashing implements EmbeddingBackend {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inputs = new AtomicInteger();
        final AtomicBoolean failing = new AtomicBoolean();
        final List<String> embedded = java.util.Collections.synchronizedList(new ArrayList<>());
        private final int dimension;
        private final int maxCharacters;

        Hashing(int dimension) {
            this(dimension, Integer.MAX_VALUE);
        }

        Hashing(int dimension, int maxCharacters) {
            this.dimension = dimension;
            this.maxCharacters = maxCharacters;
        }

        @Override
        public String describe() {
            return "test:hashing-" + dimension;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new EmbeddingException("simulated provider outage");
            }
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                if (text.length() > maxCharacters) {
                    throw new InputTooLongException("input of " + text.length() + " characters exceeds " + maxCharacters);
                }
                inputs.incrementAndGet();
                embedded.add(text);
                vectors.add(vector(text));
            }
            return vectors;
        }

        private float[] vector(String text) {
            float[] values = new float[dimension];
            String folded = " " + PolishTextAnalyzer.fold(text.replaceFirst("^(query|passage): ", "")) + " ";
            for (int index = 0; index + 3 <= folded.length(); index++) {
                String gram = folded.substring(index, index + 3);
                if (gram.isBlank()) {
                    continue;
                }
                values[Math.floorMod(gram.hashCode(), dimension)] += 1.0f;
            }
            return values;
        }
    }
}
