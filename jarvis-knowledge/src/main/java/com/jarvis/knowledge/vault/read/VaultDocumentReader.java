package com.jarvis.knowledge.vault.read;

import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.chunk.TokenCounter;
import com.jarvis.knowledge.vault.index.VaultIndexer;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads whole documents for the model, splitting long ones into explicit consecutive parts at
 * heading (then paragraph, then line) boundaries. A part never ends in the middle of a line, the
 * reader is told how many parts exist, and every heading is listed with the part that holds it,
 * so the model can never mistake part 1 for the complete procedure.
 */
public final class VaultDocumentReader {

    private final VaultPathPolicy policy;
    private final VaultMarkdownParser parser = new VaultMarkdownParser();
    private final TokenCounter counter;
    private final List<String> workflowFolders;
    private int maxCharacters = Integer.MAX_VALUE;

    /**
     * Creates the reader.
     *
     * @param policy path policy
     * @param counter token counter (estimates the chat model context cost)
     * @param workflowFolders workflow folders
     */
    public VaultDocumentReader(VaultPathPolicy policy, TokenCounter counter, List<String> workflowFolders) {
        this.policy = policy;
        this.counter = counter;
        this.workflowFolders = workflowFolders;
    }

    /**
     * Reads one part of a document.
     *
     * @param relativePath vault-relative path
     * @param documentId stable id when known
     * @param requestedPart 1-based part (values below 1 read part 1)
     * @param maxPartTokens token budget of one part
     * @return part
     */
    public PagedDocument read(String relativePath, String documentId, int requestedPart, int maxPartTokens) {
        return read(relativePath, documentId, requestedPart, maxPartTokens, Integer.MAX_VALUE);
    }

    /**
     * Reads one part of a document with token and character budgets per part.
     *
     * @param relativePath vault-relative path
     * @param documentId stable id when known
     * @param requestedPart 1-based part
     * @param maxPartTokens token budget of one part
     * @param maxPartCharacters character budget of one part
     * @return part
     */
    public PagedDocument read(String relativePath, String documentId, int requestedPart, int maxPartTokens, int maxPartCharacters) {
        this.maxCharacters = maxPartCharacters;
        String cleaned = VaultPathPolicy.clean(relativePath);
        Path file = policy.resolve(cleaned);
        if (policy.isExcluded(cleaned)) {
            throw new KnowledgeException("Document is excluded from the knowledge vault: " + cleaned);
        }
        if (!policy.isNote(cleaned) || !policy.isSafeRegularFile(file)) {
            throw new KnowledgeException("Document not found: " + cleaned);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read " + cleaned, exception);
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (policy.promptGuard() != null && policy.promptGuard().isPromptContent(raw)) {
            throw new KnowledgeException("Document is a copy of the system prompt and is not readable as knowledge: " + cleaned);
        }
        if (com.jarvis.knowledge.vault.SecretScanner.detect(raw).isPresent()) {
            throw new KnowledgeException("Document appears to contain credentials and is not given to the model: " + cleaned);
        }
        VaultNote note = parser.parse(cleaned, raw);
        List<String> lines = note.lines();
        int totalTokens = counter.count(String.join("\n", lines));
        boolean fits = totalTokens <= maxPartTokens && String.join("\n", lines).length() <= maxCharacters;
        List<int[]> ranges = fits ? List.of(new int[]{1, lines.size()}) : parts(note, maxPartTokens);
        int parts = ranges.size();
        int part = Math.max(1, Math.min(requestedPart, parts));
        int[] range = ranges.get(part - 1);
        String content = String.join("\n", lines.subList(range[0] - 1, range[1]));
        List<PagedDocument.OutlineEntry> outline = new ArrayList<>();
        for (VaultNote.Heading heading : note.headings()) {
            outline.add(new PagedDocument.OutlineEntry(heading.level(), heading.text(), heading.line(), partOf(ranges, heading.line())));
        }
        boolean workflow = VaultIndexer.isWorkflow(cleaned, note.type(), workflowFolders);
        List<String> notes = new ArrayList<>();
        if (parts == 1) {
            notes.add("Complete document (" + lines.size() + " lines).");
        } else {
            notes.add("PART " + part + " OF " + parts + " (lines " + range[0] + "-" + range[1] + " of " + lines.size()
                    + "). This is NOT the whole document.");
            if (part < parts) {
                notes.add("Read part " + (part + 1) + " next (same path, part=" + (part + 1) + "). "
                        + (workflow ? "Do not start executing this procedure until every part has been read." : "")
                        + " Do not claim to know sections of unread parts.");
            } else {
                notes.add("This is the last part.");
            }
        }
        String hash = VaultIndexer.sha256(bytes);
        return new PagedDocument(cleaned, documentId == null ? "" : documentId, note.title(), note.type(), workflow, hash, part, parts,
                parts == 1, content, range[0], range[1], lines.size(), counter.count(content), totalTokens, List.copyOf(outline),
                List.copyOf(notes));
    }

    private List<int[]> parts(VaultNote note, int maxPartTokens) {
        List<String> lines = note.lines();
        List<int[]> segments = new ArrayList<>();
        int start = 1;
        for (VaultNote.Heading heading : note.headings()) {
            if (heading.line() > start) {
                segments.add(new int[]{start, heading.line() - 1});
                start = heading.line();
            }
        }
        segments.add(new int[]{start, lines.size()});

        List<int[]> units = new ArrayList<>();
        for (int[] segment : segments) {
            if (tokens(lines, segment) <= maxPartTokens) {
                units.add(segment);
            } else {
                units.addAll(splitSegment(lines, segment, maxPartTokens));
            }
        }
        List<int[]> parts = new ArrayList<>();
        int[] current = null;
        for (int[] unit : units) {
            if (current == null) {
                current = unit.clone();
                continue;
            }
            int[] joined = {current[0], unit[1]};
            if (tokens(lines, joined) <= maxPartTokens) {
                current = joined;
            } else {
                parts.add(current);
                current = unit.clone();
            }
        }
        if (current != null) {
            parts.add(current);
        }
        return parts;
    }

    private List<int[]> splitSegment(List<String> lines, int[] segment, int maxPartTokens) {
        List<int[]> paragraphs = new ArrayList<>();
        int start = segment[0];
        for (int line = segment[0]; line <= segment[1]; line++) {
            if (lines.get(line - 1).isBlank() && line > start) {
                paragraphs.add(new int[]{start, line});
                start = line + 1;
            }
        }
        if (start <= segment[1]) {
            paragraphs.add(new int[]{start, segment[1]});
        }
        List<int[]> units = new ArrayList<>();
        for (int[] paragraph : paragraphs) {
            if (tokens(lines, paragraph) <= maxPartTokens) {
                units.add(paragraph);
                continue;
            }
            for (int line = paragraph[0]; line <= paragraph[1]; line++) {
                units.add(new int[]{line, line});
            }
        }
        return units;
    }

    private int tokens(List<String> lines, int[] range) {
        // A range over the character budget costs "infinitely many" tokens, so it is always split.
        String text = String.join("\n", lines.subList(range[0] - 1, range[1]));
        return text.length() > maxCharacters ? Integer.MAX_VALUE : counter.count(text);
    }

    private static int partOf(List<int[]> ranges, int line) {
        for (int index = 0; index < ranges.size(); index++) {
            if (line >= ranges.get(index)[0] && line <= ranges.get(index)[1]) {
                return index + 1;
            }
        }
        return ranges.size();
    }
}
