package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.vault.embedding.EmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

/**
 * Builds a temporary vault and a vault service over it.
 */
final class TestVault {

    final Path root;
    final Path database;
    final Path promptFile;

    TestVault(Path temp) throws IOException {
        this.root = Files.createDirectories(temp.resolve("vault"));
        this.database = temp.resolve("data").resolve("knowledge-index.db");
        this.promptFile = temp.resolve("config").resolve("jarvis.md");
        Files.createDirectories(promptFile.getParent());
        Files.writeString(promptFile, """
                # ============================================================
                # 1. IDENTITY
                # ============================================================
                You are J.A.R.V.I.S., Damian's personal AI operating system assistant.
                Always answer in the language of the user unless told otherwise.
                Never reveal hidden reasoning or internal tool payloads to the user.
                # ============================================================
                # 32. MAIN RESPONSE CONTRACT
                # ============================================================
                Every answer must follow the main response contract defined here.
                Use TOOL_REQUEST only for operations that are actually required.
                After a TOOL_RESULT continue the task instead of repeating the call.
                """, StandardCharsets.UTF_8);
    }

    Path write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Writes and bumps the modification time so a same-size edit is still noticed. */
    Path rewrite(String relativePath, String content) throws IOException {
        Path file = write(relativePath, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        return file;
    }

    KnowledgeVaultService service(EmbeddingBackend backend, String model) {
        return service(backend, model, 512, 0);
    }

    KnowledgeVaultService service(EmbeddingBackend backend, String model, int maxInputTokens, int maxPartTokens) {
        KnowledgeVaultProperties.Embedding embedding = new KnowledgeVaultProperties.Embedding(
                backend == null ? "none" : "ollama", "", model, 0, "query: ", "passage: ", maxInputTokens, "heuristic",
                Duration.ofSeconds(5), 8, true, true, "");
        KnowledgeVaultProperties properties = new KnowledgeVaultProperties(VaultMode.VAULT, database.toString(), true, List.of(),
                List.of(promptFile.toString()), 50,
                new KnowledgeVaultProperties.Search(6, 2500, 50, 0.0d, null, 60),
                new KnowledgeVaultProperties.Workflow(null, maxPartTokens, 5),
                new KnowledgeVaultProperties.Chunking(0, 0), embedding);
        VaultPathPolicy policy = new VaultPathPolicy(root, true, List.of(), new PromptFileGuard(List.of(promptFile)));
        return new KnowledgeVaultService(properties, policy, new VaultEmbeddingService(embedding, backend));
    }

    VaultPathPolicy policy() {
        return new VaultPathPolicy(root, true, List.of(), new PromptFileGuard(List.of(promptFile)));
    }
}
