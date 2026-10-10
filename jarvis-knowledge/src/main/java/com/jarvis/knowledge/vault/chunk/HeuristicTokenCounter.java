package com.jarvis.knowledge.vault.chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative token estimate for subword tokenizers (SentencePiece / WordPiece, e.g. XLM-R used
 * by multilingual-e5). It deliberately over-estimates Polish text: every letter run costs
 * {@code ceil(length / 3)} tokens, every digit run {@code ceil(length / 2)}, every symbol one
 * token, plus two special tokens. It is designed to err on the high side, but it is still an
 * estimate: when the provider exposes a tokenizer endpoint ({@code tokenizer: remote}) the real
 * count is used, and an input the provider rejects as too long is split further by the indexer
 * instead of being truncated.
 */
public final class HeuristicTokenCounter implements TokenCounter {

    private static final Pattern PIECES = Pattern.compile("\\p{L}+|\\p{N}+|[^\\s\\p{L}\\p{N}]");

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 2;
        }
        int tokens = 2;
        Matcher matcher = PIECES.matcher(text);
        while (matcher.find()) {
            String piece = matcher.group();
            char first = piece.charAt(0);
            if (Character.isLetter(first)) {
                tokens += (piece.length() + 2) / 3;
            } else if (Character.isDigit(first)) {
                tokens += (piece.length() + 1) / 2;
            } else {
                tokens += 1;
            }
        }
        return tokens;
    }
}
