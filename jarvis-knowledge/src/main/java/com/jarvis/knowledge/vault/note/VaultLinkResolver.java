package com.jarvis.knowledge.vault.note;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves Markdown links and wikilinks the way Obsidian does for the common cases: relative
 * Markdown paths, exact vault paths, paths without the {@code .md} extension, and bare note or
 * attachment names (shortest matching path wins when a name is not unique).
 */
public final class VaultLinkResolver {

    private final Map<String, String> byLowerPath = new HashMap<>();
    private final Map<String, List<String>> byLowerName = new HashMap<>();

    /**
     * Creates a resolver over the current vault files.
     *
     * @param relativePaths every vault-relative file path (notes and attachments)
     */
    public VaultLinkResolver(Collection<String> relativePaths) {
        for (String path : relativePaths) {
            String lower = path.toLowerCase(Locale.ROOT);
            byLowerPath.put(lower, path);
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            byLowerName.computeIfAbsent(name, key -> new ArrayList<>()).add(path);
            if (name.endsWith(".md")) {
                byLowerName.computeIfAbsent(name.substring(0, name.length() - 3), key -> new ArrayList<>()).add(path);
            }
        }
        byLowerName.values().forEach(paths -> paths.sort(Comparator.comparingInt(String::length).thenComparing(path -> path)));
    }

    /**
     * Resolves a link.
     *
     * @param fromPath path of the note containing the link
     * @param link link
     * @return resolved vault-relative path
     */
    public Optional<String> resolve(String fromPath, VaultNote.Link link) {
        String target = link.target() == null ? "" : link.target().strip().replace('\\', '/');
        if (target.isBlank()) {
            return Optional.empty();
        }
        if ("markdown".equals(link.kind()) || ("embed".equals(link.kind()) && target.contains("/") && !target.startsWith("/"))) {
            String base = fromPath.contains("/") ? fromPath.substring(0, fromPath.lastIndexOf('/') + 1) : "";
            Optional<String> relative = exact(normalize(base + target));
            if (relative.isPresent()) {
                return relative;
            }
        }
        String cleaned = target.startsWith("/") ? target.substring(1) : target;
        Optional<String> exact = exact(normalize(cleaned));
        if (exact.isPresent()) {
            return exact;
        }
        String name = cleaned.toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf('/') + 1);
        List<String> candidates = byLowerName.getOrDefault(name, List.of());
        if (cleaned.contains("/")) {
            String suffix = normalize(cleaned).toLowerCase(Locale.ROOT);
            candidates = candidates.stream()
                    .filter(path -> {
                        String lower = path.toLowerCase(Locale.ROOT);
                        return lower.endsWith(suffix) || lower.endsWith(suffix + ".md");
                    })
                    .toList();
        }
        return candidates.stream().findFirst();
    }

    private Optional<String> exact(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String found = byLowerPath.get(lower);
        if (found == null) {
            found = byLowerPath.get(lower + ".md");
        }
        return Optional.ofNullable(found);
    }

    private String normalize(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
                continue;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }
}
