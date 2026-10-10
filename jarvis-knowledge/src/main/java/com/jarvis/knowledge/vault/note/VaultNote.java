package com.jarvis.knowledge.vault.note;

import java.util.List;
import java.util.Map;

/**
 * A parsed Markdown note. Line numbers are 1-based and refer to the original file, including the
 * frontmatter block, so a fragment's line range can be opened directly in an editor.
 *
 * @param relativePath vault-relative path
 * @param frontmatterId stable {@code id} from frontmatter, or {@code ""}
 * @param title title (frontmatter title, first H1, or file name)
 * @param type document type ({@code workflow}, {@code knowledge}, ...), or {@code ""}
 * @param status document status, or {@code ""}
 * @param project project, or {@code ""}
 * @param version declared version, or {@code ""}
 * @param updated declared update date, or {@code ""}
 * @param tags frontmatter and inline tags, lowercase, without {@code #}
 * @param aliases frontmatter aliases
 * @param frontmatter raw frontmatter values (safe YAML types only)
 * @param frontmatterError YAML parse problem, or {@code ""}
 * @param lines every line of the file
 * @param bodyStartLine first line after the frontmatter
 * @param headings headings outside code fences
 * @param links Markdown links, wikilinks and embeds
 */
public record VaultNote(
        String relativePath,
        String frontmatterId,
        String title,
        String type,
        String status,
        String project,
        String version,
        String updated,
        List<String> tags,
        List<String> aliases,
        Map<String, Object> frontmatter,
        String frontmatterError,
        List<String> lines,
        int bodyStartLine,
        List<Heading> headings,
        List<Link> links
) {

    /**
     * Returns the body text (everything after the frontmatter).
     *
     * @return body
     */
    public String body() {
        if (bodyStartLine > lines.size()) {
            return "";
        }
        return String.join("\n", lines.subList(bodyStartLine - 1, lines.size()));
    }

    /**
     * Heading.
     *
     * @param level 1-6
     * @param text heading text
     * @param line 1-based line
     */
    public record Heading(int level, String text, int line) {
    }

    /**
     * Link found in the note.
     *
     * @param kind {@code markdown}, {@code wikilink} or {@code embed}
     * @param target raw link target (decoded, without anchor)
     * @param anchor heading / block anchor, or {@code ""}
     * @param label display text, or {@code ""}
     * @param line 1-based line
     */
    public record Link(String kind, String target, String anchor, String label, int line) {
    }
}
