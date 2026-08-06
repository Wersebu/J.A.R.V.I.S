package com.jarvis.tools.knowledge.filing;

import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.workspace.KnowledgeNodeType;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceNode;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Default semantic destination planner for knowledge filing.
 */
@Service
public class DefaultKnowledgeDestinationPlanner implements KnowledgeDestinationPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultKnowledgeDestinationPlanner.class);

    private final KnowledgeFilingProperties properties;

    /**
     * Creates the planner.
     *
     * @param properties filing properties
     */
    public DefaultKnowledgeDestinationPlanner(KnowledgeFilingProperties properties) {
        this.properties = properties;
    }

    @Override
    public KnowledgeDestinationPlan plan(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, RetrievalResult searchResult) {
        if (!knowledge.worthSaving()) {
            return new KnowledgeDestinationPlan("SKIP", "", "", "", "", "Knowledge is not worth saving.", 1.0d, List.of());
        }
        List<String> alternatives = new ArrayList<>();
        String target = targetPath(knowledge, tree, alternatives);
        String existingId = existingDocumentId(target, tree).orElse("");
        String operation = existingId.isBlank() || !properties.updateExisting() ? "CREATE_DOCUMENT" : "UPDATE_DOCUMENT";
        if (knowledge.confidence() < properties.categoryConfidenceThreshold() && properties.allowInboxFallback()) {
            target = "Inbox/Unclassified-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md";
            operation = "CREATE_DOCUMENT";
        }
        for (RetrievalDocument document : searchResult == null ? List.<RetrievalDocument>of() : searchResult.documents()) {
            alternatives.add(document.relativePath());
        }
        KnowledgeDestinationPlan plan = new KnowledgeDestinationPlan(
                operation,
                target,
                existingId,
                documentTitle(knowledge, target),
                section(knowledge),
                reason(knowledge, operation),
                existingId.isBlank() ? knowledge.confidence() : Math.min(0.99d, knowledge.confidence() + 0.05d),
                alternatives.stream().distinct().limit(8).toList()
        );
        LOGGER.info("""
                [KNOWLEDGE_FILING]
                selectedPath="{}"
                operation={}
                reason="{}"
                """, plan.targetPath(), plan.operation(), plan.reason());
        return plan;
    }

    private String targetPath(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, List<String> alternatives) {
        return switch (knowledge.kind()) {
            case BIRTHDAY, PERSON_FACT, RELATIONSHIP -> personPath(knowledge, tree, alternatives);
            case HARDWARE, DEVICE -> hardwarePath(knowledge, tree, alternatives);
            case VEHICLE -> vehiclePath(knowledge, tree, alternatives);
            case PROJECT -> folder(tree, List.of("Projects", "Project", "Projekty"), "Projects") + "/" + safeFile(knowledge.subject()) + "/Status.md";
            case PROCEDURE -> folder(tree, List.of("Procedures", "Procedury"), "Procedures") + "/" + safeFile(knowledge.subject()) + ".md";
            case CONFIGURATION -> folder(tree, List.of("Configurations", "Config", "Konfiguracje"), "Configurations") + "/" + safeFile(knowledge.subject()) + ".md";
            default -> properties.allowInboxFallback()
                    ? "Inbox/Unclassified-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md"
                    : folder(tree, List.of("Research", "Notes"), "Research") + "/" + safeFile(knowledge.subject().isBlank() ? "Knowledge" : knowledge.subject()) + ".md";
        };
    }

    private String personPath(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, List<String> alternatives) {
        String root = folder(tree, List.of("People", "Persons", "Osoby", "Contacts", "Personalities"), "People");
        String target = root + "/" + safeFile(knowledge.subject()) + ".md";
        alternatives.add(target);
        return target;
    }

    private String hardwarePath(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, List<String> alternatives) {
        String normalized = normalize(knowledge.subject() + " " + knowledge.normalizedFact());
        if (normalized.contains("server") || normalized.contains("serwer") || normalized.contains("jarvisserver")) {
            String root = folder(tree, List.of("Infrastructure", "Infra", "Server", "Servers", "Infrastruktura"), "Infrastructure");
            String target = root + "/JarvisServer/Hardware.md";
            alternatives.add(target);
            return target;
        }
        String root = folder(tree, List.of("Personal", "People", "Osoby"), "Personal");
        String target = root + "/Damian/PC/Hardware.md";
        alternatives.add(target);
        return target;
    }

    private String vehiclePath(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, List<String> alternatives) {
        String root = folder(tree, List.of("Vehicles", "Cars", "Auta", "Samochody", "Pojazdy"), "Vehicles");
        String target = normalize(knowledge.subject()).contains("audi")
                ? root + "/Audi/A8-D3.md"
                : root + "/" + safeFile(knowledge.subject()) + ".md";
        alternatives.add(target);
        return target;
    }

    private String folder(KnowledgeWorkspaceTree tree, List<String> aliases, String fallback) {
        if (tree == null || tree.root() == null) {
            return fallback;
        }
        for (String alias : aliases) {
            Optional<KnowledgeWorkspaceNode> node = findFolder(tree.root(), alias);
            if (node.isPresent()) {
                return node.get().relativePath();
            }
        }
        return fallback;
    }

    private Optional<KnowledgeWorkspaceNode> findFolder(KnowledgeWorkspaceNode node, String name) {
        if (node.type() == KnowledgeNodeType.FOLDER && normalize(node.name()).equals(normalize(name))) {
            return Optional.of(node);
        }
        return node.children().stream()
                .map(child -> findFolder(child, name))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private Optional<String> existingDocumentId(String targetPath, KnowledgeWorkspaceTree tree) {
        if (tree == null || tree.root() == null) {
            return Optional.empty();
        }
        return findDocument(tree.root(), targetPath).map(node -> node.documentId() == null ? "" : node.documentId().toString());
    }

    private Optional<KnowledgeWorkspaceNode> findDocument(KnowledgeWorkspaceNode node, String targetPath) {
        if (node.relativePath().equalsIgnoreCase(targetPath)) {
            return Optional.of(node);
        }
        return node.children().stream()
                .map(child -> findDocument(child, targetPath))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private String section(ExtractedKnowledge knowledge) {
        return switch (knowledge.kind()) {
            case BIRTHDAY -> "Urodziny";
            case HARDWARE, DEVICE -> "Hardware";
            case VEHICLE -> "Informacje";
            case PREFERENCE -> "Preferencje";
            case PROJECT -> "Status";
            default -> "Informacje";
        };
    }

    private String reason(ExtractedKnowledge knowledge, String operation) {
        if (knowledge.kind() == KnowledgeKind.BIRTHDAY) {
            return "Stable fact about a named person.";
        }
        if (knowledge.kind() == KnowledgeKind.HARDWARE) {
            return "Stable hardware configuration.";
        }
        return operation.equals("UPDATE_DOCUMENT") ? "Existing canonical document found." : "Canonical destination selected from semantic category.";
    }

    private String documentTitle(ExtractedKnowledge knowledge, String targetPath) {
        if (!knowledge.subject().isBlank()) {
            return knowledge.subject();
        }
        String name = targetPath.substring(targetPath.lastIndexOf('/') + 1).replaceFirst("\\.md$", "");
        return name.replace('-', ' ');
    }

    private String safeFile(String value) {
        String safe = value == null || value.isBlank() ? "Knowledge" : value.trim();
        safe = Normalizer.normalize(safe, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        safe = safe.replace('ł', 'l').replace('Ł', 'L');
        safe = safe.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return safe.isBlank() ? "Knowledge" : safe;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
    }
}
