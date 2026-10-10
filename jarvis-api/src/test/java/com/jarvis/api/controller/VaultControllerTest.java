package com.jarvis.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.knowledge.DefaultKnowledgeService;
import com.jarvis.knowledge.InMemoryKnowledgeIndex;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.LoggingKnowledgeEventPublisher;
import com.jarvis.knowledge.Sha256Hasher;
import com.jarvis.knowledge.SupportedFileTypes;
import com.jarvis.knowledge.extract.DocumentExtractorRegistry;
import com.jarvis.knowledge.extract.MarkdownExtractor;
import com.jarvis.knowledge.extract.TextExtractor;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.vault.KnowledgeVaultProperties;
import com.jarvis.knowledge.vault.KnowledgeVaultService;
import com.jarvis.knowledge.vault.PromptFileGuard;
import com.jarvis.knowledge.vault.VaultMode;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.edit.VaultDocumentService;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceProperties;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of /api/v1/vault as consumed by the Windows client.
 */
class VaultControllerTest {

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private KnowledgeVaultService vault;
    private MockMvc mvc;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(temp.resolve("vault"));
        Files.createDirectories(root.resolve("knowledge"));
        Files.writeString(root.resolve("knowledge/Serwer.md"), "# Serwer\n\nRTX 4060 Ti 16 GB współdzielona z modelem i głosem.\n");
        KnowledgeVaultProperties properties = new KnowledgeVaultProperties(VaultMode.VAULT, temp.resolve("index.db").toString(), true,
                List.of(), List.of(), 50, null, null, null, null);
        VaultPathPolicy policy = new VaultPathPolicy(root, true, List.of(), new PromptFileGuard(List.of()));
        vault = new KnowledgeVaultService(properties, policy, new VaultEmbeddingService(properties.embedding(), null));
        vault.startSynchronously();
        InMemoryKnowledgeIndex legacyIndex = new InMemoryKnowledgeIndex();
        DefaultKnowledgeService legacy = new DefaultKnowledgeService(new KnowledgeProperties(root.toString(), false, 500, 300),
                legacyIndex, new SupportedFileTypes(), new DocumentExtractorRegistry(List.of(new MarkdownExtractor(), new TextExtractor())),
                new Sha256Hasher(), new LoggingKnowledgeEventPublisher());
        legacy.reindex();
        VaultDocumentService documents = new VaultDocumentService(policy, legacy, legacyIndex, new KnowledgeWorkspaceProperties(null, null, null),
                vault, new StaticListableBeanFactory().getBeanProvider(KnowledgeWorkspaceService.class));
        mvc = MockMvcBuilders.standaloneSetup(new VaultController(vault, documents,
                query -> new RetrievalResult(query, 0, 0, List.of()))).build();
    }

    @AfterEach
    void tearDown() {
        vault.close();
    }

    @Test
    void statusTreeAndSearchExposeWhatTheWindowNeeds() throws Exception {
        mvc.perform(get("/api/v1/vault/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("VAULT"))
                .andExpect(jsonPath("$.searchableDocuments").value(1))
                .andExpect(jsonPath("$.embedding.enabled").value(false));
        mvc.perform(get("/api/v1/vault/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].name").value("knowledge"))
                .andExpect(jsonPath("$.children[0].children[0].path").value("knowledge/Serwer.md"));
        mvc.perform(get("/api/v1/vault/search").param("q", "karta RTX współdzielona").param("mode", "hybrid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveMode").value("TEXT"))
                .andExpect(jsonPath("$.hits[0].path").value("knowledge/Serwer.md"))
                .andExpect(jsonPath("$.hits[0].startLine").value(1))
                .andExpect(jsonPath("$.semanticError").isNotEmpty());
    }

    @Test
    void staleSaveReturns409WithCurrentContentAndFreshSaveSucceeds() throws Exception {
        String body = mvc.perform(get("/api/v1/vault/document").param("path", "knowledge/Serwer.md"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String version = mapper.readTree(body).get("version").asText();
        Files.writeString(root.resolve("knowledge/Serwer.md"), "# Serwer\n\nZmienione w Obsidianie.\n");

        mvc.perform(put("/api/v1/vault/document").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "path", "knowledge/Serwer.md", "content", "# Serwer\n\nMoja wersja.\n", "expectedVersion", version))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.currentContent").value("# Serwer\n\nZmienione w Obsidianie.\n"));
        assertThat(Files.readString(root.resolve("knowledge/Serwer.md"))).contains("Zmienione w Obsidianie");

        JsonNode current = mapper.readTree(mvc.perform(get("/api/v1/vault/document").param("path", "knowledge/Serwer.md"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(put("/api/v1/vault/document").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "path", "knowledge/Serwer.md", "content", "# Serwer\n\nScalone.\n", "expectedVersion", current.get("version").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNotEmpty());
        mvc.perform(get("/api/v1/vault/history").param("path", "knowledge/Serwer.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].author").value("USER"));
    }

    @Test
    void pathEscapesAreRejected() throws Exception {
        mvc.perform(get("/api/v1/vault/document").param("path", "../index.db"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/vault/document").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("path", "../../poza.md", "content", "x"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/vault/read").param("path", "/etc/passwd"))
                .andExpect(status().isBadRequest());
        assertThat(Files.exists(temp.resolve("poza.md"))).isFalse();
    }

    @Test
    void createMoveAndReindex() throws Exception {
        mvc.perform(post("/api/v1/vault/folder").contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"workflows\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/vault/document").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("path", "workflows/Kopia zapasowa", "content",
                                "---\ntype: workflow\n---\n# Kopia zapasowa\n\n1. Zatrzymaj usługę.\n2. Skopiuj katalog data.\n"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("workflows/Kopia zapasowa.md"));
        mvc.perform(post("/api/v1/vault/move").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("from", "workflows/Kopia zapasowa.md", "to", "knowledge/Serwer.md"))))
                .andExpect(status().isConflict());
        // Creating the note already scheduled an automatic (debounced) reconcile; a manual reindex is idempotent.
        mvc.perform(post("/api/v1/vault/reindex")).andExpect(status().isOk()).andExpect(jsonPath("$.changes.errors").value(0));
        mvc.perform(get("/api/v1/vault/workflows").param("q", "kopia zapasowa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("workflows/Kopia zapasowa.md"));
        mvc.perform(get("/api/v1/vault/read").param("path", "workflows/Kopia zapasowa.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true));
    }
}
