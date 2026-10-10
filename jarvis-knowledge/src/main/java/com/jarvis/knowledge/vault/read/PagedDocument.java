package com.jarvis.knowledge.vault.read;

import java.util.List;

/**
 * One part of a document read. Long documents are never truncated silently: they are returned in
 * consecutive parts with an outline that says which part holds which section.
 *
 * @param path vault-relative path
 * @param documentId stable document id ({@code ""} when not indexed yet)
 * @param title title
 * @param type document type
 * @param workflow whether the document is a workflow
 * @param contentHash version token of the file that was read
 * @param part 1-based part number
 * @param parts total parts
 * @param complete true when this read returned the whole document
 * @param content text of this part, exactly as in the file
 * @param startLine first line of this part
 * @param endLine last line of this part
 * @param totalLines lines in the document
 * @param partTokens estimated tokens of this part
 * @param totalTokens estimated tokens of the whole document
 * @param outline headings with the part that contains them
 * @param notes reading instructions for the model / UI
 */
public record PagedDocument(
        String path,
        String documentId,
        String title,
        String type,
        boolean workflow,
        String contentHash,
        int part,
        int parts,
        boolean complete,
        String content,
        int startLine,
        int endLine,
        int totalLines,
        int partTokens,
        int totalTokens,
        List<OutlineEntry> outline,
        List<String> notes
) {

    /**
     * Outline entry.
     *
     * @param level heading level
     * @param heading heading text
     * @param line line
     * @param part part containing the heading
     */
    public record OutlineEntry(int level, String heading, int line, int part) {
    }
}
