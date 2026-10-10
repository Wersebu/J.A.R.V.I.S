package com.jarvis.knowledge.vault.note;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Obsidian-compatible Markdown: optional YAML frontmatter, ATX headings, Markdown links,
 * wikilinks ({@code [[Note]]}, {@code [[Note#Heading|alias]]}), embeds ({@code ![[image.png]]})
 * and inline {@code #tags}. Documents without frontmatter are fully supported.
 */
public final class VaultMarkdownParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})");
    private static final Pattern WIKILINK = Pattern.compile("(!?)\\[\\[([^\\]|#\\n]*)(#[^\\]|\\n]*)?(?:\\|([^\\]\\n]*))?]]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(!?)\\[([^\\]\\n]*)]\\(\\s*<?([^)>\\s]+(?:\\s[^)>\"]+)*?)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern INLINE_TAG = Pattern.compile("(?:^|\\s)#([\\p{L}\\p{N}_/-]*\\p{L}[\\p{L}\\p{N}_/-]*)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

    /**
     * Parses a note.
     *
     * @param relativePath vault-relative path
     * @param raw file content (UTF-8 decoded)
     * @return parsed note
     */
    public VaultNote parse(String relativePath, String raw) {
        String content = raw == null ? "" : raw;
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        List<String> lines = List.of(content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        Map<String, Object> frontmatter = Map.of();
        String frontmatterError = "";
        int bodyStart = 1;
        if (!lines.isEmpty() && lines.getFirst().strip().equals("---")) {
            int end = -1;
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index).strip();
                if (line.equals("---") || line.equals("...")) {
                    end = index;
                    break;
                }
            }
            if (end > 0) {
                String yaml = String.join("\n", lines.subList(1, end));
                try {
                    frontmatter = parseYaml(yaml);
                } catch (RuntimeException exception) {
                    frontmatterError = "Invalid YAML frontmatter: " + firstLine(exception.getMessage());
                }
                bodyStart = end + 2;
            }
        }

        List<VaultNote.Heading> headings = new ArrayList<>();
        List<VaultNote.Link> links = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>(stringList(frontmatter.get("tags")));
        tags.addAll(stringList(frontmatter.get("tag")));
        boolean inFence = false;
        String fenceMarker = "";
        for (int index = bodyStart - 1; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                String marker = fence.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker.substring(0, 1);
                } else if (marker.startsWith(fenceMarker)) {
                    inFence = false;
                }
                continue;
            }
            if (inFence) {
                continue;
            }
            int lineNumber = index + 1;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                headings.add(new VaultNote.Heading(heading.group(1).length(), heading.group(2).strip(), lineNumber));
            }
            String withoutCode = INLINE_CODE.matcher(line).replaceAll(" ");
            collectLinks(withoutCode, lineNumber, links);
            if (!heading.matches()) {
                Matcher tag = INLINE_TAG.matcher(withoutCode);
                while (tag.find()) {
                    tags.add(tag.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }

        String title = string(frontmatter.get("title"));
        if (title.isBlank()) {
            title = headings.stream().filter(heading -> heading.level() == 1).map(VaultNote.Heading::text).findFirst()
                    .orElse(fileTitle(relativePath));
        }
        List<String> normalizedTags = tags.stream()
                .map(tag -> tag.startsWith("#") ? tag.substring(1) : tag)
                .map(tag -> tag.toLowerCase(Locale.ROOT).strip())
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
        return new VaultNote(
                relativePath,
                string(frontmatter.get("id")),
                title,
                string(frontmatter.get("type")).toLowerCase(Locale.ROOT),
                string(frontmatter.get("status")).toLowerCase(Locale.ROOT),
                string(frontmatter.get("project")),
                string(frontmatter.get("version")),
                string(frontmatter.get("updated")),
                normalizedTags,
                stringList(frontmatter.get("aliases")),
                frontmatter,
                frontmatterError,
                lines,
                Math.min(bodyStart, lines.size() + 1),
                List.copyOf(headings),
                List.copyOf(links)
        );
    }

    /**
     * Returns the title derived from a file name.
     *
     * @param relativePath path
     * @return file name without extension
     */
    public static String fileTitle(String relativePath) {
        String name = relativePath == null ? "" : relativePath;
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void collectLinks(String line, int lineNumber, List<VaultNote.Link> links) {
        Matcher wiki = WIKILINK.matcher(line);
        while (wiki.find()) {
            String target = wiki.group(2) == null ? "" : wiki.group(2).strip();
            String anchor = wiki.group(3) == null ? "" : wiki.group(3).substring(1).strip();
            String label = wiki.group(4) == null ? "" : wiki.group(4).strip();
            links.add(new VaultNote.Link(wiki.group(1).isEmpty() ? "wikilink" : "embed", target, anchor, label, lineNumber));
        }
        String withoutWiki = WIKILINK.matcher(line).replaceAll(" ");
        Matcher markdown = MARKDOWN_LINK.matcher(withoutWiki);
        while (markdown.find()) {
            String rawTarget = markdown.group(3).strip();
            String lower = rawTarget.toLowerCase(Locale.ROOT);
            if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) {
                continue;
            }
            String anchor = "";
            int hash = rawTarget.indexOf('#');
            if (hash >= 0) {
                anchor = rawTarget.substring(hash + 1);
                rawTarget = rawTarget.substring(0, hash);
            }
            if (rawTarget.isBlank()) {
                continue;
            }
            links.add(new VaultNote.Link(markdown.group(1).isEmpty() ? "markdown" : "embed", decode(rawTarget), anchor,
                    markdown.group(2).strip(), lineNumber));
        }
    }

    private Map<String, Object> parseYaml(String yaml) {
        if (yaml.isBlank()) {
            return Map.of();
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(256 * 1024);
        Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("frontmatter is not a key/value mapping");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key).strip().toLowerCase(Locale.ROOT), plain(value)));
        return values;
    }

    private Object plain(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString().replace("T00:00:00Z", "");
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::plain).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), plain(nested)));
            return copy;
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null).map(item -> String.valueOf(item).strip())
                    .filter(item -> !item.isBlank()).toList();
        }
        String text = String.valueOf(value);
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    private static String string(Object value) {
        if (value == null || value instanceof Collection<?> || value instanceof Map<?, ?>) {
            return "";
        }
        return String.valueOf(value).strip();
    }

    private static String decode(String target) {
        try {
            return URLDecoder.decode(target.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return target;
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
