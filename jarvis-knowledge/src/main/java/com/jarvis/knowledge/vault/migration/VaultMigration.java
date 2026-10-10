package com.jarvis.knowledge.vault.migration;

import com.jarvis.knowledge.KnowledgeDocumentIds;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.index.PolishTextAnalyzer;
import com.jarvis.knowledge.vault.index.VaultIndexer;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares an existing knowledge folder for use as an Obsidian vault.
 *
 * <p>Dry-run by default: it only reports what it would do. {@link #apply} first copies the whole
 * vault to a backup folder, then adds stable {@code id} frontmatter (reusing ids that existing
 * version history is stored under), applies explicitly planned moves and frontmatter values, and
 * creates the suggested folders. It never deletes or merges documents: identical and similar
 * documents, and file names that contradict the document title, are reported for a human
 * decision. Running it again changes nothing (every action checks whether it is already done).
 * Conversation history lives elsewhere and is never touched.
 */
public final class VaultMigration {

    /** Suggested Obsidian layout; existing folders stay where they are. */
    public static final List<String> SUGGESTED_FOLDERS = List.of("workflows", "projects", "knowledge", "preferences", "decisions");
    private static final Pattern ID_LINE = Pattern.compile("^id\\s*:.*$");
    private static final double SIMILAR_LINES = 0.5d;

    private final Path vault;
    private final VaultPathPolicy policy;
    private final Path historyRoot;
    private final VaultMarkdownParser parser = new VaultMarkdownParser();

    /**
     * Creates the migration.
     *
     * @param policy vault path policy (root = vault)
     * @param historyDirectory history directory name inside the vault
     */
    public VaultMigration(VaultPathPolicy policy, String historyDirectory) {
        this.policy = policy;
        this.vault = policy.root();
        this.historyRoot = vault.resolve(historyDirectory == null || historyDirectory.isBlank() ? ".history" : historyDirectory);
    }

    /**
     * Builds the migration plan without changing anything.
     *
     * @param plan optional explicit plan (moves, frontmatter values, folders); may be empty
     * @param addIds whether missing frontmatter ids are added
     * @return report
     */
    public Report plan(Plan plan, boolean addIds) {
        List<Note> notes = scan();
        Map<String, List<String>> historyIdsByPath = historyIds();
        List<Item> items = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Map<String, String> movedTo = new HashMap<>();
        for (Move move : plan.moves()) {
            movedTo.put(VaultPathPolicy.clean(move.from()), VaultPathPolicy.clean(move.to()));
        }

        for (Note note : notes) {
            List<String> actions = new ArrayList<>();
            String target = movedTo.getOrDefault(note.path(), note.path());
            if (!target.equals(note.path())) {
                if (policy.isExcluded(target)) {
                    findings.add(new Finding("ERROR", note.path(), "", "Planned target is excluded from the vault: " + target));
                    target = note.path();
                } else if (Files.exists(vault.resolve(target))) {
                    findings.add(new Finding("CONFLICT", note.path(), target, "Planned target already exists; move skipped"));
                    target = note.path();
                } else {
                    actions.add("MOVE -> " + target);
                }
            }
            String id = note.parsed().frontmatterId();
            String idSource = "frontmatter";
            if (id.isBlank() && addIds) {
                List<String> historyIds = historyIdsByPath.getOrDefault(note.path(), List.of());
                String pathId = KnowledgeDocumentIds.of(note.path(), "").toString();
                if (historyIds.contains(pathId) || historyIds.isEmpty()) {
                    id = pathId;
                    idSource = historyIds.contains(pathId) ? "history" : "generated (deterministic from path)";
                } else {
                    id = historyIds.getFirst();
                    idSource = "history";
                    if (historyIds.size() > 1) {
                        findings.add(new Finding("INFO", note.path(), "", "Version history exists under several ids " + historyIds
                                + " (ids used to change on every restart); the newest is kept, older folders stay in .history"));
                    }
                }
                actions.add("ADD_ID " + id + " (" + idSource + ")");
            }
            Map<String, Object> values = plan.frontmatter().getOrDefault(note.path(), Map.of());
            for (Map.Entry<String, Object> value : values.entrySet()) {
                Object current = note.parsed().frontmatter().get(value.getKey().toLowerCase(Locale.ROOT));
                if (current == null || !String.valueOf(current).equals(String.valueOf(value.getValue()))) {
                    actions.add("SET " + value.getKey() + ": " + value.getValue());
                }
            }
            items.add(new Item(note.path(), target, id, idSource, actions.isEmpty() ? List.of("NONE") : List.copyOf(actions),
                    note.parsed().title(), note.parsed().type()));
            String nameWarning = nameMismatch(note);
            if (!nameWarning.isEmpty()) {
                findings.add(new Finding("NAME_MISMATCH", note.path(), "", nameWarning));
            }
            if (!note.parsed().frontmatterError().isEmpty()) {
                findings.add(new Finding("WARN", note.path(), "", note.parsed().frontmatterError()));
            }
        }
        findings.addAll(similarities(notes));
        for (String excluded : excludedFiles()) {
            findings.add(new Finding("EXCLUDED", excluded, "", "Not part of the indexed vault (internal, temporary, backup, secret or system prompt file)"));
        }
        List<String> folders = new ArrayList<>();
        for (String folder : plan.createFolders().isEmpty() ? SUGGESTED_FOLDERS : plan.createFolders()) {
            if (!Files.isDirectory(vault.resolve(VaultPathPolicy.clean(folder)))) {
                folders.add(VaultPathPolicy.clean(folder));
            }
        }
        return new Report(vault.toString(), Instant.now().toString(), true, "", items, findings, folders);
    }

    /**
     * Applies a plan after copying the vault to a backup folder.
     *
     * @param plan plan
     * @param addIds whether missing ids are added
     * @param backupParent folder receiving the timestamped backup
     * @return report of what was applied
     * @throws IOException on failure (the backup stays in place)
     */
    public Report apply(Plan plan, boolean addIds, Path backupParent) throws IOException {
        Report planned = plan(plan, addIds);
        boolean changes = !planned.foldersToCreate().isEmpty()
                || planned.items().stream().anyMatch(item -> !item.actions().equals(List.of("NONE")));
        if (!changes) {
            return new Report(planned.vault(), planned.createdAt(), false, "", planned.items(), planned.findings(), List.of());
        }
        Path backup = backupParent.resolve("vault-" + Instant.now().toString().replace(':', '-'));
        copyTree(vault, backup);
        for (String folder : planned.foldersToCreate()) {
            Files.createDirectories(policy.resolve(folder));
        }
        for (Item item : planned.items()) {
            Path source = policy.resolve(item.source());
            String content = Files.readString(source, StandardCharsets.UTF_8);
            String updated = content;
            for (String action : item.actions()) {
                if (action.startsWith("ADD_ID ")) {
                    updated = setFrontmatter(updated, "id", item.id());
                } else if (action.startsWith("SET ")) {
                    String assignment = action.substring(4);
                    int colon = assignment.indexOf(':');
                    updated = setFrontmatter(updated, assignment.substring(0, colon).strip(), assignment.substring(colon + 1).strip());
                }
            }
            if (!updated.equals(content)) {
                Path temp = source.resolveSibling(".~jarvis-migration-" + UUID.randomUUID() + ".tmp");
                Files.writeString(temp, updated, StandardCharsets.UTF_8);
                Files.move(temp, source, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!item.target().equals(item.source())) {
                Path target = policy.resolve(item.target());
                Files.createDirectories(target.getParent());
                Files.move(source, target);
            }
        }
        return new Report(planned.vault(), planned.createdAt(), false, backup.toString(), planned.items(), planned.findings(),
                planned.foldersToCreate());
    }

    /**
     * Loads an explicit plan file.
     *
     * @param file YAML file ({@code moves}, {@code frontmatter}, {@code createFolders})
     * @return plan
     * @throws IOException when unreadable
     */
    @SuppressWarnings("unchecked")
    public static Plan loadPlan(Path file) throws IOException {
        if (file == null) {
            return new Plan(List.of(), Map.of(), List.of());
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file, StandardCharsets.UTF_8));
        if (!(loaded instanceof Map<?, ?> rawRoot)) {
            return new Plan(List.of(), Map.of(), List.of());
        }
        Map<String, Object> root = (Map<String, Object>) rawRoot;
        List<Move> moves = new ArrayList<>();
        for (Object entry : (List<Object>) root.getOrDefault("moves", List.of())) {
            Map<String, Object> move = (Map<String, Object>) entry;
            moves.add(new Move(String.valueOf(move.get("from")), String.valueOf(move.get("to"))));
        }
        Map<String, Map<String, Object>> frontmatter = new LinkedHashMap<>();
        for (Object entry : (List<Object>) root.getOrDefault("frontmatter", List.of())) {
            Map<String, Object> values = (Map<String, Object>) entry;
            frontmatter.put(VaultPathPolicy.clean(String.valueOf(values.get("path"))), (Map<String, Object>) values.getOrDefault("set", Map.of()));
        }
        List<String> folders = ((List<Object>) root.getOrDefault("createFolders", List.of())).stream().map(String::valueOf).toList();
        return new Plan(moves, frontmatter, folders);
    }

    /**
     * Inserts or replaces one top-level frontmatter key, keeping everything else byte-for-byte.
     *
     * @param content note content
     * @param key key
     * @param value value
     * @return updated content
     */
    public static String setFrontmatter(String content, String key, String value) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String bom = content.startsWith("﻿") ? "﻿" : "";
        String body = bom.isEmpty() ? content : content.substring(1);
        String rendered = key + ": " + yamlScalar(value);
        String[] lines = body.split("\\r?\\n", -1);
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                if (line.strip().equals("---") || line.strip().equals("...")) {
                    List<String> updated = new ArrayList<>(List.of(lines));
                    updated.add(index, rendered);
                    return bom + String.join(newline, updated);
                }
                if (line.matches("^" + Pattern.quote(key) + "\\s*:.*$")) {
                    if (line.equals(rendered)) {
                        return content;
                    }
                    lines[index] = rendered;
                    return bom + String.join(newline, lines);
                }
            }
        }
        return bom + "---" + newline + rendered + newline + "---" + newline + body;
    }

    private static String yamlScalar(String value) {
        if (value.matches("[\\p{L}\\p{N}_./-]+") && !value.matches("(?i)true|false|yes|no|null|~|\\d+(\\.\\d+)?")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<Note> scan() {
        List<Note> notes = new ArrayList<>();
        try {
            Files.walkFileTree(vault, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return !directory.equals(vault) && policy.isExcluded(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    String relative = policy.relativize(file);
                    if (!policy.isExcluded(file) && policy.isNote(relative) && policy.isSafeRegularFile(file)) {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        if (policy.promptGuard() == null || !policy.promptGuard().isPromptContent(content)) {
                            notes.add(new Note(relative, content, parser.parse(relative, content)));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan " + vault + ": " + exception.getMessage(), exception);
        }
        notes.sort(Comparator.comparing(Note::path));
        return notes;
    }

    private List<String> excludedFiles() {
        List<String> excluded = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(vault, 3)) {
            paths.filter(path -> !path.equals(vault))
                    .filter(path -> !path.startsWith(historyRoot) && !path.toString().contains(".git"))
                    .forEach(path -> {
                        String relative = policy.relativize(path);
                        boolean parentExcluded = path.getParent() != null && !path.getParent().equals(vault) && policy.isExcluded(path.getParent());
                        if (!parentExcluded && policy.isExcluded(relative) && excluded.size() < 50) {
                            excluded.add(relative + (Files.isDirectory(path) ? "/" : ""));
                        }
                    });
        } catch (IOException exception) {
            excluded.add("(listing failed: " + exception.getMessage() + ")");
        }
        return excluded;
    }

    private Map<String, List<String>> historyIds() {
        Map<String, List<Map.Entry<String, String>>> byPath = new HashMap<>();
        if (!Files.isDirectory(historyRoot)) {
            return Map.of();
        }
        try (Stream<Path> metas = Files.walk(historyRoot, 2)) {
            metas.filter(path -> path.getFileName().toString().endsWith(".meta")).forEach(meta -> {
                try {
                    String relativePath = "";
                    String timestamp = "";
                    for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                        if (line.startsWith("relativePath=")) {
                            relativePath = line.substring("relativePath=".length()).strip();
                        } else if (line.startsWith("timestamp=")) {
                            timestamp = line.substring("timestamp=".length()).strip();
                        }
                    }
                    String id = meta.getParent().getFileName().toString();
                    if (!relativePath.isEmpty() && id.matches("[0-9a-fA-F-]{36}")) {
                        byPath.computeIfAbsent(relativePath, key -> new ArrayList<>()).add(Map.entry(id, timestamp));
                    }
                } catch (IOException ignored) {
                    // unreadable history entries are simply not reused
                }
            });
        } catch (IOException exception) {
            return Map.of();
        }
        Map<String, List<String>> ids = new HashMap<>();
        byPath.forEach((path, entries) -> ids.put(path, entries.stream()
                .sorted(Map.Entry.<String, String>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .distinct()
                .toList()));
        return ids;
    }

    private List<Finding> similarities(List<Note> notes) {
        List<Finding> findings = new ArrayList<>();
        Map<String, String> byHash = new HashMap<>();
        for (Note note : notes) {
            String normalized = normalizedBody(note);
            String hash = VaultIndexer.sha256(normalized.getBytes(StandardCharsets.UTF_8));
            String first = byHash.putIfAbsent(hash, note.path());
            if (first != null && !normalized.isBlank()) {
                findings.add(new Finding("DUPLICATE", note.path(), first, "Identical content (frontmatter and whitespace ignored). Not merged automatically."));
            }
        }
        for (int left = 0; left < notes.size(); left++) {
            for (int right = left + 1; right < notes.size(); right++) {
                Note a = notes.get(left);
                Note b = notes.get(right);
                String bodyA = normalizedBody(a);
                String bodyB = normalizedBody(b);
                if (bodyA.isBlank() || bodyB.isBlank() || bodyA.equals(bodyB)) {
                    continue;
                }
                String shorter = bodyA.length() <= bodyB.length() ? bodyA : bodyB;
                String longer = shorter == bodyA ? bodyB : bodyA;
                Note shorterNote = shorter == bodyA ? a : b;
                Note longerNote = shorterNote == a ? b : a;
                if (shorter.length() > 200 && longer.startsWith(shorter.substring(0, Math.min(shorter.length(), shorter.length() - 1)))) {
                    findings.add(new Finding("TRUNCATED_COPY", shorterNote.path(), longerNote.path(), String.format(Locale.ROOT,
                            "Its whole content (%d characters) is the beginning of the longer document (%d characters): looks like an "
                                    + "older or truncated copy. Decide which one is authoritative; nothing is merged automatically.",
                            shorter.length(), longer.length())));
                    continue;
                }
                double overlap = lineOverlap(a.content(), b.content());
                if (overlap >= SIMILAR_LINES) {
                    findings.add(new Finding("SIMILAR", a.path(), b.path(), String.format(Locale.ROOT,
                            "%.0f%% of substantive lines are shared. Review for conflicting instructions; not merged automatically.", overlap * 100)));
                }
            }
        }
        return findings;
    }

    private String nameMismatch(Note note) {
        String fileName = VaultMarkdownParser.fileTitle(note.path());
        Set<String> nameWords = words(fileName.replaceAll("([a-z])([A-Z])", "$1 $2"));
        Set<String> titleWords = words(note.parsed().title());
        nameWords.removeAll(Set.of("workflow", "notatka", "note", "md"));
        if (nameWords.isEmpty() || titleWords.isEmpty() || fileName.equalsIgnoreCase(note.parsed().title())) {
            return "";
        }
        for (String word : nameWords) {
            for (String title : titleWords) {
                if (word.startsWith(title) || title.startsWith(word) || commonPrefix(word, title) >= 5) {
                    return "";
                }
            }
        }
        return "File name '" + fileName + "' shares no words with the document title '" + note.parsed().title()
                + "'. Either the name is just in another language, or it describes a different document than its content - check it.";
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String stem : PolishTextAnalyzer.stems(text)) {
            if (stem.length() >= 3) {
                words.add(stem);
            }
        }
        return words;
    }

    private static int commonPrefix(String left, String right) {
        int length = 0;
        while (length < Math.min(left.length(), right.length()) && left.charAt(length) == right.charAt(length)) {
            length++;
        }
        return length;
    }

    private String normalizedBody(Note note) {
        return note.parsed().body().replaceAll("\\s+", " ").strip();
    }

    private static double lineOverlap(String left, String right) {
        Set<String> a = significant(left);
        Set<String> b = significant(right);
        if (a.size() < 5 || b.size() < 5) {
            return 0.0d;
        }
        long shared = a.stream().filter(b::contains).count();
        return (double) shared / Math.min(a.size(), b.size());
    }

    private static Set<String> significant(String content) {
        Set<String> lines = new HashSet<>();
        for (String line : content.split("\\R")) {
            String normalized = line.strip().toLowerCase(Locale.ROOT);
            if (normalized.length() >= 20) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (directory.getFileName() != null && directory.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record Note(String path, String content, VaultNote parsed) {
    }

    /**
     * Explicit migration plan.
     *
     * @param moves planned moves
     * @param frontmatter frontmatter values per path
     * @param createFolders folders to create (empty = suggested layout)
     */
    public record Plan(List<Move> moves, Map<String, Map<String, Object>> frontmatter, List<String> createFolders) {
    }

    /**
     * Planned move.
     *
     * @param from source path
     * @param to target path
     */
    public record Move(String from, String to) {
    }

    /**
     * Source to target mapping of one note.
     *
     * @param source current path
     * @param target path after migration
     * @param id stable id after migration
     * @param idSource where the id comes from
     * @param actions actions ({@code NONE} when already migrated)
     * @param title title
     * @param type type
     */
    public record Item(String source, String target, String id, String idSource, List<String> actions, String title, String type) {
    }

    /**
     * Finding that needs a human decision.
     *
     * @param kind {@code DUPLICATE}, {@code TRUNCATED_COPY}, {@code SIMILAR}, {@code NAME_MISMATCH}, {@code CONFLICT}, ...
     * @param path document
     * @param related related document
     * @param message explanation
     */
    public record Finding(String kind, String path, String related, String message) {
    }

    /**
     * Migration report.
     *
     * @param vault vault root
     * @param createdAt timestamp
     * @param dryRun true when nothing was changed
     * @param backup backup folder ({@code ""} for dry-runs or when nothing had to change)
     * @param items source to target mapping
     * @param findings findings
     * @param foldersToCreate folders created / to create
     */
    public record Report(String vault, String createdAt, boolean dryRun, String backup, List<Item> items, List<Finding> findings,
                         List<String> foldersToCreate) {

        /**
         * Renders the report as Markdown.
         *
         * @return Markdown
         */
        public String toMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("# Raport migracji vault").append(dryRun ? " (DRY-RUN - nic nie zmieniono)" : "").append("\n\n");
            out.append("- Vault: `").append(vault).append("`\n- Utworzono: ").append(createdAt).append('\n');
            if (!backup.isEmpty()) {
                out.append("- Kopia zapasowa: `").append(backup).append("`\n");
            }
            out.append("\n## Źródło → cel\n\n| Źródło | Cel | id | Pochodzenie id | Akcje |\n|---|---|---|---|---|\n");
            for (Item item : items) {
                out.append("| ").append(item.source()).append(" | ").append(item.target()).append(" | `").append(item.id()).append("` | ")
                        .append(item.idSource()).append(" | ").append(String.join("; ", item.actions())).append(" |\n");
            }
            out.append("\n## Foldery do utworzenia\n\n");
            out.append(foldersToCreate.isEmpty() ? "- (brak)\n" : String.join("\n", foldersToCreate.stream().map(folder -> "- " + folder + "/").toList()) + "\n");
            out.append("\n## Do decyzji człowieka (nic nie jest scalane ani usuwane automatycznie)\n\n");
            if (findings.isEmpty()) {
                out.append("- (brak)\n");
            }
            for (Finding finding : findings) {
                out.append("- **").append(finding.kind()).append("** `").append(finding.path()).append('`');
                if (!finding.related().isEmpty()) {
                    out.append(" ↔ `").append(finding.related()).append('`');
                }
                out.append(": ").append(finding.message()).append('\n');
            }
            return out.toString();
        }
    }
}
