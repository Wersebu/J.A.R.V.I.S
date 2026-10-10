package com.jarvis.knowledge.vault.chunk;

import com.jarvis.knowledge.vault.note.VaultNote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a note into fragments along its structure: first by headings, then by paragraphs (code
 * fences and blank-line separated blocks stay intact), packing neighbouring paragraphs of the same
 * section up to a target size. A block longer than the embedding model limit is split by lines,
 * then sentences, then words. Every size decision is made with a {@link TokenCounter} on the exact
 * text that will be embedded (prefix + title/heading context + fragment), never on characters.
 */
public final class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…:;])\\s+");

    private final TokenCounter counter;
    private final int targetTokens;
    private final int maxTokens;
    private final int minTokens;

    /**
     * Creates a chunker.
     *
     * @param counter token counter of the embedding model
     * @param targetTokens preferred fragment size
     * @param maxTokens hard input limit of the embedding model
     * @param minTokens fragments smaller than this are merged with the previous fragment of the same section
     */
    public MarkdownChunker(TokenCounter counter, int targetTokens, int maxTokens, int minTokens) {
        if (maxTokens < 32) {
            throw new IllegalArgumentException("maxTokens must be at least 32");
        }
        this.counter = counter;
        this.maxTokens = maxTokens;
        this.targetTokens = Math.min(Math.max(16, targetTokens), maxTokens);
        this.minTokens = Math.max(0, minTokens);
    }

    /**
     * Chunks a note.
     *
     * @param note parsed note
     * @param passagePrefix model passage prefix (e5: {@code "passage: "})
     * @return fragments in document order
     */
    public List<VaultChunk> chunk(VaultNote note, String passagePrefix) {
        String prefix = passagePrefix == null ? "" : passagePrefix;
        List<Section> sections = sections(note);
        List<VaultChunk> chunks = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            boolean headingOnly = section.blocks().stream().allMatch(Block::heading);
            boolean laterContent = sections.subList(index + 1, sections.size()).stream()
                    .anyMatch(next -> next.blocks().stream().anyMatch(block -> !block.heading()));
            if (headingOnly && laterContent) {
                continue;
            }
            String context = context(note.title(), section.headingPath());
            chunkSection(note, section, prefix, context, chunks);
        }
        return chunks;
    }

    private void chunkSection(VaultNote note, Section section, String prefix, String context, List<VaultChunk> out) {
        List<Block> pending = new ArrayList<>();
        int sectionStart = out.size();
        for (Block block : section.blocks()) {
            List<Block> candidate = new ArrayList<>(pending);
            candidate.add(block);
            if (tokens(prefix, context, text(note, candidate)) <= targetTokens) {
                pending = candidate;
                continue;
            }
            if (!pending.isEmpty()) {
                emit(note, section, prefix, context, pending, out);
                pending = new ArrayList<>();
            }
            if (tokens(prefix, context, text(note, List.of(block))) <= maxTokens) {
                pending.add(block);
            } else {
                for (Piece piece : split(note, block, prefix, context)) {
                    out.add(chunk(out.size(), section, piece.startLine(), piece.endLine(), piece.text(), prefix, context));
                }
            }
        }
        if (!pending.isEmpty()) {
            String text = text(note, pending);
            int tokens = tokens(prefix, context, text);
            if (tokens < minTokens && out.size() > sectionStart) {
                VaultChunk previous = out.getLast();
                String merged = joinLines(note, previous.startLine(), pending.getLast().endLine());
                if (tokens(prefix, context, merged) <= maxTokens && previous.headingPath().equals(section.headingPath())) {
                    out.set(out.size() - 1, chunk(previous.ordinal(), section, previous.startLine(), pending.getLast().endLine(),
                            merged, prefix, context));
                    return;
                }
            }
            emit(note, section, prefix, context, pending, out);
        }
    }

    private void emit(VaultNote note, Section section, String prefix, String context, List<Block> blocks, List<VaultChunk> out) {
        int start = blocks.getFirst().startLine();
        int end = blocks.getLast().endLine();
        out.add(chunk(out.size(), section, start, end, joinLines(note, start, end), prefix, context));
    }

    private VaultChunk chunk(int ordinal, Section section, int start, int end, String text, String prefix, String context) {
        String embeddingText = embeddingText(prefix, context, text);
        return new VaultChunk(ordinal, section.headingPath(), start, end, text, embeddingText, counter.count(embeddingText));
    }

    private List<Piece> split(VaultNote note, Block block, String prefix, String context) {
        List<Piece> pieces = new ArrayList<>();
        List<Piece> lines = new ArrayList<>();
        for (int line = block.startLine(); line <= block.endLine(); line++) {
            lines.add(new Piece(line, line, note.lines().get(line - 1)));
        }
        Piece current = null;
        for (Piece line : lines) {
            if (tokens(prefix, context, line.text()) > maxTokens) {
                if (current != null) {
                    pieces.add(current);
                    current = null;
                }
                pieces.addAll(splitLine(line, prefix, context));
                continue;
            }
            if (current == null) {
                current = line;
                continue;
            }
            String joined = current.text() + "\n" + line.text();
            if (tokens(prefix, context, joined) <= targetTokens) {
                current = new Piece(current.startLine(), line.endLine(), joined);
            } else {
                pieces.add(current);
                current = line;
            }
        }
        if (current != null) {
            pieces.add(current);
        }
        return pieces;
    }

    private List<Piece> splitLine(Piece line, String prefix, String context) {
        List<String> units = new ArrayList<>(List.of(SENTENCE_END.split(line.text())));
        List<String> fitted = new ArrayList<>();
        for (String unit : units) {
            if (tokens(prefix, context, unit) <= maxTokens) {
                fitted.add(unit);
            } else {
                fitted.addAll(splitWords(unit, prefix, context));
            }
        }
        List<Piece> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : fitted) {
            String joined = current.isEmpty() ? unit : current + " " + unit;
            if (tokens(prefix, context, joined) <= targetTokens || current.isEmpty()) {
                current = new StringBuilder(joined);
            } else {
                pieces.add(new Piece(line.startLine(), line.endLine(), current.toString()));
                current = new StringBuilder(unit);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(new Piece(line.startLine(), line.endLine(), current.toString()));
        }
        return pieces;
    }

    private List<String> splitWords(String text, String prefix, String context) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (tokens(prefix, context, candidate) <= maxTokens) {
                current = new StringBuilder(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                parts.add(current.toString());
            }
            if (tokens(prefix, context, word) <= maxTokens) {
                current = new StringBuilder(word);
            } else {
                parts.addAll(splitCharacters(word, prefix, context));
                current = new StringBuilder();
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private List<String> splitCharacters(String word, String prefix, String context) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            while (end > start + 1 && tokens(prefix, context, word.substring(start, end)) > maxTokens) {
                end = start + Math.max(1, (end - start) / 2);
            }
            parts.add(word.substring(start, end));
            start = end;
        }
        return parts;
    }

    private List<Section> sections(VaultNote note) {
        List<Section> sections = new ArrayList<>();
        List<VaultNote.Heading> stack = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        List<String> currentPath = List.of();
        int blockStart = -1;
        boolean inFence = false;
        String fenceMarker = "";
        List<String> lines = note.lines();
        for (int index = note.bodyStartLine() - 1; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index);
            Matcher fence = FENCE.matcher(line);
            if (inFence) {
                if (fence.find() && fence.group(1).startsWith(fenceMarker)) {
                    inFence = false;
                    blocks.add(new Block(blockStart, lineNumber, false));
                    blockStart = -1;
                }
                continue;
            }
            if (fence.find()) {
                if (blockStart > 0) {
                    blocks.add(new Block(blockStart, lineNumber - 1, false));
                }
                inFence = true;
                fenceMarker = fence.group(1).substring(0, 1);
                blockStart = lineNumber;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (blockStart > 0) {
                    blocks.add(new Block(blockStart, lineNumber - 1, false));
                    blockStart = -1;
                }
                if (!blocks.isEmpty()) {
                    sections.add(new Section(currentPath, blocks));
                }
                int level = heading.group(1).length();
                while (!stack.isEmpty() && stack.getLast().level() >= level) {
                    stack.removeLast();
                }
                stack.add(new VaultNote.Heading(level, heading.group(2).strip(), lineNumber));
                currentPath = stack.stream().map(VaultNote.Heading::text).toList();
                blocks = new ArrayList<>();
                blocks.add(new Block(lineNumber, lineNumber, true));
                continue;
            }
            if (line.isBlank()) {
                if (blockStart > 0) {
                    blocks.add(new Block(blockStart, lineNumber - 1, false));
                    blockStart = -1;
                }
                continue;
            }
            if (blockStart < 0) {
                blockStart = lineNumber;
            }
        }
        if (blockStart > 0) {
            blocks.add(new Block(blockStart, lines.size(), false));
        }
        if (!blocks.isEmpty()) {
            sections.add(new Section(currentPath, blocks));
        }
        // Trailing blank lines inside a block (unterminated fence) are harmless; empty sections are dropped.
        return sections.stream().filter(section -> !section.blocks().isEmpty()).toList();
    }

    private String text(VaultNote note, List<Block> blocks) {
        return joinLines(note, blocks.getFirst().startLine(), blocks.getLast().endLine());
    }

    private String joinLines(VaultNote note, int start, int end) {
        return String.join("\n", note.lines().subList(start - 1, end)).strip();
    }

    private int tokens(String prefix, String context, String text) {
        return counter.count(embeddingText(prefix, context, text));
    }

    private static String embeddingText(String prefix, String context, String text) {
        return prefix + context + "\n\n" + text;
    }

    private static String context(String title, List<String> headingPath) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank() && (headingPath.isEmpty() || !headingPath.getFirst().equalsIgnoreCase(title))) {
            parts.add(title);
        }
        parts.addAll(headingPath);
        return String.join(" > ", parts);
    }

    private record Block(int startLine, int endLine, boolean heading) {
    }

    private record Section(List<String> headingPath, List<Block> blocks) {
    }

    private record Piece(int startLine, int endLine, String text) {
    }
}
