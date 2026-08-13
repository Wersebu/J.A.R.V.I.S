package com.jarvis.tools.knowledge;

import com.jarvis.knowledge.workspace.KnowledgeNodeType;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving LIST_TREE/LIST_FOLDER return a flat, tool-agnostic
 * {@code entries:[{type,path,name}]} structure the model can copy an exact "path" from directly
 * into READ_DOCUMENT — instead of a raw recursive node object that got silently dropped by tool
 * result compaction.
 */
class KnowledgeToolFlattenTest {

    @Test
    void listTreeFlattensNestedDocumentWithExactCopyablePath() throws Exception {
        KnowledgeTool tool = new KnowledgeTool(null, null, null);
        KnowledgeWorkspaceNode file = new KnowledgeWorkspaceNode(
                "knowledge-document:hardware/graphics_card.txt", UUID.randomUUID(), KnowledgeNodeType.DOCUMENT,
                "graphics_card.txt", "hardware/graphics_card.txt", List.of(), 42L, Instant.now(), true);
        KnowledgeWorkspaceNode folder = new KnowledgeWorkspaceNode(
                "knowledge-folder:hardware", null, KnowledgeNodeType.FOLDER,
                "hardware", "hardware", List.of(file), 0L, Instant.now(), false);
        KnowledgeWorkspaceNode root = new KnowledgeWorkspaceNode(
                "knowledge-root:/", null, KnowledgeNodeType.ROOT, "", "", List.of(folder), 0L, Instant.now(), false);

        List<Map<String, Object>> entries = flatten(tool, root, true);

        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("file");
            assertThat(entry.get("path")).isEqualTo("hardware/graphics_card.txt");
        });
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("folder");
            assertThat(entry.get("path")).isEqualTo("hardware");
        });
    }

    @Test
    void listFolderNonRecursiveDoesNotDescendIntoSubfolders() throws Exception {
        KnowledgeTool tool = new KnowledgeTool(null, null, null);
        KnowledgeWorkspaceNode nestedFile = new KnowledgeWorkspaceNode(
                "knowledge-document:hardware/gpu/graphics_card.txt", UUID.randomUUID(), KnowledgeNodeType.DOCUMENT,
                "graphics_card.txt", "hardware/gpu/graphics_card.txt", List.of(), 10L, Instant.now(), true);
        KnowledgeWorkspaceNode nestedFolder = new KnowledgeWorkspaceNode(
                "knowledge-folder:hardware/gpu", null, KnowledgeNodeType.FOLDER,
                "gpu", "hardware/gpu", List.of(nestedFile), 0L, Instant.now(), false);
        KnowledgeWorkspaceNode hardware = new KnowledgeWorkspaceNode(
                "knowledge-folder:hardware", null, KnowledgeNodeType.FOLDER,
                "hardware", "hardware", List.of(nestedFolder), 0L, Instant.now(), false);

        List<Map<String, Object>> entries = flatten(tool, hardware, false);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("path")).isEqualTo("hardware/gpu");
        assertThat(entries.get(0).get("type")).isEqualTo("folder");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flatten(KnowledgeTool tool, KnowledgeWorkspaceNode node, boolean recursive) throws Exception {
        Method flatten = KnowledgeTool.class.getDeclaredMethod("flatten", KnowledgeWorkspaceNode.class, boolean.class);
        flatten.setAccessible(true);
        return (List<Map<String, Object>>) flatten.invoke(tool, node, recursive);
    }
}
