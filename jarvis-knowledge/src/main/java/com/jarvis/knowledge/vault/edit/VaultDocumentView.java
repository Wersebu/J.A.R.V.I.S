package com.jarvis.knowledge.vault.edit;

import com.jarvis.knowledge.workspace.KnowledgeDraft;

import java.util.List;
import java.util.Map;

/**
 * Document as shown in the Windows editor.
 *
 * @param path vault-relative path
 * @param name file name
 * @param content content ({@code ""} for attachments)
 * @param version version token to send back when saving (sha256 of the bytes read)
 * @param size size in bytes
 * @param modified last modification (ISO)
 * @param title title
 * @param frontmatterId stable frontmatter id, or {@code ""}
 * @param type type
 * @param status status
 * @param tags tags
 * @param project project
 * @param documentVersion declared version
 * @param updated declared update date
 * @param frontmatter raw frontmatter
 * @param frontmatterError YAML problem, or {@code ""}
 * @param links outgoing links with resolution
 * @param backlinks notes linking here
 * @param index chunk index state of this document
 * @param historyId id under which version history is stored
 * @param drafts pending AI drafts targeting this document
 * @param attachment true for non-note files
 * @param editable whether the editor may save it
 * @param warning warning to show (e.g. secret detected)
 */
public record VaultDocumentView(
        String path,
        String name,
        String content,
        String version,
        long size,
        String modified,
        String title,
        String frontmatterId,
        String type,
        String status,
        List<String> tags,
        String project,
        String documentVersion,
        String updated,
        Map<String, Object> frontmatter,
        String frontmatterError,
        List<LinkView> links,
        List<String> backlinks,
        Map<String, Object> index,
        String historyId,
        List<KnowledgeDraft> drafts,
        boolean attachment,
        boolean editable,
        String warning
) {

    /**
     * Outgoing link.
     *
     * @param kind {@code markdown}, {@code wikilink} or {@code embed}
     * @param target raw target
     * @param anchor heading anchor
     * @param label label
     * @param resolvedPath resolved vault path, or {@code ""}
     * @param exists whether the target exists
     * @param line line
     */
    public record LinkView(String kind, String target, String anchor, String label, String resolvedPath, boolean exists, int line) {
    }
}
