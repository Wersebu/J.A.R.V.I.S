package com.jarvis.knowledge.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.DefaultKnowledgeService;
import com.jarvis.knowledge.InMemoryKnowledgeIndex;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.LoggingKnowledgeEventPublisher;
import com.jarvis.knowledge.Sha256Hasher;
import com.jarvis.knowledge.SupportedFileTypes;
import com.jarvis.knowledge.extract.DocumentExtractorRegistry;
import com.jarvis.knowledge.extract.MarkdownExtractor;
import com.jarvis.knowledge.extract.TextExtractor;
import com.jarvis.knowledge.retrieval.DefaultEmbeddingKnowledgeRetriever;
import com.jarvis.knowledge.retrieval.DefaultHybridKnowledgeRetriever;
import com.jarvis.knowledge.retrieval.KeywordKnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.vault.chunk.HeuristicTokenCounter;
import com.jarvis.knowledge.vault.embedding.EmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.OllamaEmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.OpenAiCompatibleEmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;
import com.jarvis.knowledge.vault.index.PolishTextAnalyzer;
import com.jarvis.knowledge.vault.search.VaultSearchHit;
import com.jarvis.knowledge.vault.search.VaultSearchQuery;
import com.jarvis.knowledge.vault.search.VaultSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval benchmark: the current Jarvis mechanism (document-level keyword + preview-embedding
 * search, optionally followed by reading the top document) against the new fragment search, on
 * a fixed Polish fixture vault with 34 questions (32 answerable, 2 without an answer in the vault).
 *
 * <p>Retriever metrics (always run, no model needed): source hit@1/hit@3, whether the expected
 * answer text is present in the context handed to the model, search time and context size.
 * Embeddings are used only when {@code JARVIS_BENCH_EMBEDDING_PROVIDER} (ollama|openai),
 * {@code JARVIS_BENCH_EMBEDDING_URL} and {@code JARVIS_BENCH_EMBEDDING_MODEL} are set; otherwise both
 * systems run keyword-only and the report says so.
 *
 * <p>Model answer metrics run only when {@code JARVIS_BENCH_OLLAMA_URL} and
 * {@code JARVIS_BENCH_CHAT_MODEL} are set; nothing is reported for them otherwise.
 */
class VaultRetrievalBenchmarkTest {

    /** NativeToolLoopService.MAX_COMPACT_CONTENT_CHARS applied to knowledge tool results in LEGACY mode. */
    private static final int LEGACY_TOOL_CONTENT_CHARS = 2500;

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HeuristicTokenCounter tokens = new HeuristicTokenCounter();

    @Test
    void compareCurrentMechanismWithFragmentSearch() throws Exception {
        Path vault = copyFixture();
        List<Question> questions = questions();
        EmbeddingBackend backend = benchmarkBackend();
        String embeddingLabel = backend == null ? "brak (oba systemy tylko słowa kluczowe)" : backend.describe();

        // Current mechanism: the same beans Core wires today.
        InMemoryKnowledgeIndex legacyIndex = new InMemoryKnowledgeIndex();
        new DefaultKnowledgeService(new KnowledgeProperties(vault.toString(), false, 500, 300), legacyIndex, new SupportedFileTypes(),
                new DocumentExtractorRegistry(List.of(new MarkdownExtractor(), new TextExtractor())), new Sha256Hasher(),
                new LoggingKnowledgeEventPublisher()).reindex();
        DefaultHybridKnowledgeRetriever legacy = new DefaultHybridKnowledgeRetriever(new KeywordKnowledgeRetriever(legacyIndex, noopBus()),
                new DefaultEmbeddingKnowledgeRetriever(legacyIndex, legacyProvider(backend)));

        KnowledgeVaultProperties.Embedding embedding = embeddingSettings(backend);
        KnowledgeVaultProperties properties = new KnowledgeVaultProperties(VaultMode.VAULT, temp.resolve("index.db").toString(), true,
                List.of(), List.of(), 50, new KnowledgeVaultProperties.Search(6, 2500, 50,
                envDouble("JARVIS_BENCH_MIN_SIMILARITY", 0.0d), null, 60), null, null, embedding);
        try (KnowledgeVaultService fragments = new KnowledgeVaultService(properties,
                new VaultPathPolicy(vault, true, List.of(), new PromptFileGuard(List.of())), new VaultEmbeddingService(embedding, backend))) {
            fragments.startSynchronously();

            List<Row> legacySearch = new ArrayList<>();
            List<Row> legacyRead = new ArrayList<>();
            List<Row> fragmentRows = new ArrayList<>();
            for (Question question : questions) {
                long started = System.nanoTime();
                RetrievalResult result = legacy.retrieve(question.question());
                long legacyMs = (System.nanoTime() - started) / 1_000_000L;
                List<String> legacyPaths = result.documents().stream().map(RetrievalDocument::relativePath).toList();
                StringBuilder searchContext = new StringBuilder();
                for (RetrievalDocument document : result.documents()) {
                    searchContext.append("Source: ").append(document.relativePath()).append('\n').append(document.preview()).append("\n\n");
                }
                legacySearch.add(new Row(question, legacyPaths, searchContext.toString(), legacyMs, legacyPaths.isEmpty()));
                // READ_DOCUMENT as the model really receives it today: NativeToolLoopService.compactData cuts
                // "content" of non-agent tool results to 2500 characters.
                String topDocument = legacyPaths.isEmpty() ? "" : Files.readString(vault.resolve(legacyPaths.getFirst()), StandardCharsets.UTF_8);
                String readContext = searchContext + topDocument.substring(0, Math.min(topDocument.length(), LEGACY_TOOL_CONTENT_CHARS));
                legacyRead.add(new Row(question, legacyPaths, readContext, legacyMs, legacyPaths.isEmpty()));

                started = System.nanoTime();
                VaultSearchResult search = fragments.search(VaultSearchQuery.of(question.question()), null, null);
                long fragmentMs = (System.nanoTime() - started) / 1_000_000L;
                List<String> paths = new ArrayList<>(new LinkedHashSet<>(search.hits().stream().map(VaultSearchHit::path).toList()));
                StringBuilder context = new StringBuilder();
                for (VaultSearchHit hit : search.hits()) {
                    context.append("Source: ").append(hit.path()).append(':').append(hit.startLine()).append('-').append(hit.endLine())
                            .append('\n').append(hit.text()).append("\n\n");
                }
                fragmentRows.add(new Row(question, paths, context.toString(), fragmentMs, search.noResults()));
            }

            StringBuilder report = new StringBuilder("# Benchmark wyszukiwania wiedzy\n\n");
            report.append("- Pytania: ").append(questions.size()).append(" (").append(answerable(questions)).append(" z odpowiedzią w vault, ")
                    .append(questions.size() - answerable(questions)).append(" bez odpowiedzi)\n");
            report.append("- Vault testowy: ").append(countFiles(vault)).append(" dokumentów (src/test/resources/vault-benchmark)\n");
            report.append("- Embeddingi: ").append(embeddingLabel).append("\n");
            report.append("- Tokeny liczone zachowawczym estymatorem HeuristicTokenCounter (nie tokenizerem modelu czatu)\n\n");
            report.append("## Retriever (bez modelu)\n\n");
            report.append("| System | Źródło @1 | Źródło @3 | Odpowiedź w kontekście | Brak wyniku dla pytań bez odpowiedzi | Czas śr. [ms] | Czas p95 [ms] | Kontekst śr. [tokeny] | Kontekst max [tokeny] |\n");
            report.append("|---|---|---|---|---|---|---|---|---|\n");
            report.append(summary("Obecny: SEARCH_CONTENT (podglądy 500 zn.)", legacySearch));
            report.append(summary("Obecny: SEARCH_CONTENT + READ_DOCUMENT top-1 (2500 zn.)", legacyRead));
            report.append(summary("Nowy: fragmenty (VAULT)", fragmentRows));
            report.append("\n## Szczegóły pytań (✓ = oczekiwany dokument w top-3 / odpowiedź w kontekście)\n\n");
            report.append("| # | Pytanie | Oczekiwany dokument | Obecny (szukaj) | Obecny (+czytaj) | Nowy |\n|---|---|---|---|---|---|\n");
            for (int index = 0; index < questions.size(); index++) {
                Question question = questions.get(index);
                report.append("| ").append(index + 1).append(" | ").append(question.question()).append(" | ")
                        .append(question.expectedPath() == null ? "— (brak w vault)" : question.expectedPath()).append(" | ")
                        .append(mark(legacySearch.get(index))).append(" | ").append(mark(legacyRead.get(index))).append(" | ")
                        .append(mark(fragmentRows.get(index))).append(" |\n");
            }

            String ollama = System.getenv("JARVIS_BENCH_OLLAMA_URL");
            String chatModel = System.getenv("JARVIS_BENCH_CHAT_MODEL");
            if (ollama != null && !ollama.isBlank() && chatModel != null && !chatModel.isBlank()) {
                report.append("\n## Odpowiedzi modelu (").append(chatModel).append(" @ ").append(ollama).append(")\n\n");
                report.append("| System | Poprawne odpowiedzi | Poprawne „brak danych” | Czas śr. [s] | Przekroczenia czasu (3 min, liczone jako błąd) |\n|---|---|---|---|---|\n");
                report.append(answers("Obecny: SEARCH_CONTENT + READ_DOCUMENT top-1 (2500 zn.)", legacyRead, ollama, chatModel));
                report.append(answers("Nowy: fragmenty (VAULT)", fragmentRows, ollama, chatModel));
            } else {
                report.append("\n## Odpowiedzi modelu\n\nNIE WYKONANO: ustaw JARVIS_BENCH_OLLAMA_URL i JARVIS_BENCH_CHAT_MODEL, aby zmierzyć "
                        + "kompletność odpowiedzi modelu. Żadne wyniki nie są tu wpisane.\n");
            }

            Path out = Path.of(System.getProperty("java.io.tmpdir"), "jarvis-vault-benchmark");
            Files.createDirectories(out);
            Files.writeString(out.resolve("report.md"), report.toString(), StandardCharsets.UTF_8);
            System.out.println(report);
            System.out.println("[BENCHMARK] report written to " + out.resolve("report.md"));

            assertThat(fragmentRows).hasSize(questions.size());
        }
    }

    private String summary(String name, List<Row> rows) {
        List<Row> answerable = rows.stream().filter(row -> row.question().expectedPath() != null).toList();
        List<Row> unanswerable = rows.stream().filter(row -> row.question().expectedPath() == null).toList();
        long hit1 = answerable.stream().filter(row -> !row.paths().isEmpty() && row.paths().getFirst().equals(row.question().expectedPath())).count();
        long hit3 = answerable.stream().filter(row -> row.paths().stream().limit(3).anyMatch(row.question().expectedPath()::equals)).count();
        long complete = answerable.stream().filter(this::answerInContext).count();
        long empty = unanswerable.stream().filter(Row::noResults).count();
        List<Long> times = rows.stream().map(Row::millis).sorted().toList();
        double average = times.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = times.isEmpty() ? 0 : times.get(Math.min(times.size() - 1, (int) Math.ceil(times.size() * 0.95) - 1));
        int[] contextTokens = rows.stream().mapToInt(row -> tokens.count(row.context())).toArray();
        double averageTokens = java.util.Arrays.stream(contextTokens).average().orElse(0);
        int maxTokens = java.util.Arrays.stream(contextTokens).max().orElse(0);
        return String.format(Locale.ROOT, "| %s | %d/%d | %d/%d | %d/%d | %d/%d | %.1f | %d | %.0f | %d |%n", name, hit1, answerable.size(), hit3,
                answerable.size(), complete, answerable.size(), empty, unanswerable.size(), average, p95, averageTokens, maxTokens);
    }

    private String mark(Row row) {
        if (row.question().expectedPath() == null) {
            return row.noResults() ? "✓ brak wyników" : "✗ " + row.paths().size() + " dok.";
        }
        boolean source = row.paths().stream().limit(3).anyMatch(row.question().expectedPath()::equals);
        return (source ? "✓" : "✗") + " / " + (answerInContext(row) ? "✓" : "✗");
    }

    private boolean answerInContext(Row row) {
        String context = PolishTextAnalyzer.fold(row.context());
        return !row.question().expectedAnswers().isEmpty()
                && row.question().expectedAnswers().stream().allMatch(answer -> context.contains(PolishTextAnalyzer.fold(answer)));
    }

    private String answers(String name, List<Row> rows, String ollama, String model) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        int correct = 0;
        int timeouts = 0;
        int correctMissing = 0;
        int answerable = 0;
        long totalMs = 0;
        for (Row row : rows) {
            String system = "Odpowiadasz wyłącznie na podstawie KONTEKSTU. Jeśli odpowiedzi nie ma w kontekście, odpowiedz dokładnie: BRAK DANYCH. "
                    + "Odpowiedz jednym krótkim zdaniem po polsku.";
            String user = "KONTEKST:\n" + row.context() + "\nPYTANIE: " + row.question().question();
            Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("model", model, "stream", false, "messages", List.of(
                    Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)),
                    "options", Map.of("temperature", 0, "num_ctx", 8192, "num_predict", 1024)));
            String think = System.getenv("JARVIS_BENCH_THINK");
            if (think != null && !think.isBlank()) {
                body.put("think", think);
            }
            long started = System.nanoTime();
            String answer;
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(ollama.replaceAll("/+$", "") + "/api/chat"))
                        .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
                answer = response.statusCode() == 200 ? mapper.readTree(response.body()).path("message").path("content").asText("") : "";
            } catch (java.net.http.HttpTimeoutException timeout) {
                answer = "";
                timeouts++;
            }
            totalMs += (System.nanoTime() - started) / 1_000_000L;
            String folded = PolishTextAnalyzer.fold(answer);
            if (row.question().expectedPath() == null) {
                if (folded.contains("brak danych")) {
                    correctMissing++;
                }
            } else {
                answerable++;
                if (row.question().expectedAnswers().stream().allMatch(expected -> folded.contains(PolishTextAnalyzer.fold(expected)))) {
                    correct++;
                }
            }
        }
        return String.format(Locale.ROOT, "| %s | %d/%d | %d/%d | %.1f | %d |%n", name, correct, answerable, correctMissing, rows.size() - answerable,
                totalMs / 1000.0d / Math.max(1, rows.size()), timeouts);
    }

    private EmbeddingBackend benchmarkBackend() {
        String provider = System.getenv("JARVIS_BENCH_EMBEDDING_PROVIDER");
        String url = System.getenv("JARVIS_BENCH_EMBEDDING_URL");
        String model = System.getenv("JARVIS_BENCH_EMBEDDING_MODEL");
        if (provider == null || url == null || model == null || provider.isBlank()) {
            return null;
        }
        return "ollama".equalsIgnoreCase(provider)
                ? new OllamaEmbeddingBackend(url, model, true, Duration.ofMinutes(2))
                : new OpenAiCompatibleEmbeddingBackend(url, model, "", true, Duration.ofMinutes(2));
    }

    private KnowledgeVaultProperties.Embedding embeddingSettings(EmbeddingBackend backend) {
        if (backend == null) {
            return new KnowledgeVaultProperties.Embedding("none", "", "", 0, "", "", 512, "heuristic", null, 16, true, true, "");
        }
        String provider = System.getenv("JARVIS_BENCH_EMBEDDING_PROVIDER");
        return new KnowledgeVaultProperties.Embedding(provider, System.getenv("JARVIS_BENCH_EMBEDDING_URL"),
                System.getenv("JARVIS_BENCH_EMBEDDING_MODEL"), 0, envString("JARVIS_BENCH_EMBEDDING_QUERY_PREFIX", ""),
                envString("JARVIS_BENCH_EMBEDDING_PASSAGE_PREFIX", ""), (int) envDouble("JARVIS_BENCH_EMBEDDING_MAX_TOKENS", 512),
                "openai".equalsIgnoreCase(provider) ? "remote" : "heuristic", Duration.ofMinutes(2), 16, true, true, "");
    }

    /** The current mechanism embeds raw text through the Core EmbeddingProvider contract (no prefixes). */
    private EmbeddingProvider legacyProvider(EmbeddingBackend backend) {
        return new EmbeddingProvider() {
            @Override
            public String provider() {
                return backend == null ? "unavailable" : "benchmark";
            }

            @Override
            public String model() {
                return backend == null ? "nomic-embed-text (not pulled)" : backend.describe();
            }

            @Override
            public EmbeddingVector embed(String text) {
                if (backend == null) {
                    throw new IllegalStateException("embedding provider unavailable");
                }
                float[] vector = backend.embed(List.of(text)).getFirst();
                List<Double> values = new ArrayList<>(vector.length);
                for (float value : vector) {
                    values.add((double) value);
                }
                return new EmbeddingVector(model(), values, 0);
            }
        };
    }

    private Path copyFixture() throws IOException, URISyntaxException {
        Path source = Path.of(VaultRetrievalBenchmarkTest.class.getResource("/vault-benchmark").toURI());
        Path target = temp.resolve("vault");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    private List<Question> questions() throws IOException {
        try (InputStream input = VaultRetrievalBenchmarkTest.class.getResourceAsStream("/vault-benchmark-questions.json")) {
            List<Map<String, Object>> raw = mapper.readValue(input, new TypeReference<>() { });
            List<Question> questions = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                @SuppressWarnings("unchecked")
                List<String> answers = (List<String>) item.getOrDefault("expectedAnswers", List.of());
                questions.add(new Question((String) item.get("question"), (String) item.get("expectedPath"), answers));
            }
            return questions;
        }
    }

    private static long answerable(List<Question> questions) {
        return questions.stream().filter(question -> question.expectedPath() != null).count();
    }

    private static long countFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static double envDouble(String name, double fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String envString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static CognitiveEventBus noopBus() {
        return new CognitiveEventBus() {
            @Override
            public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
            }

            @Override
            public void finishRequest() {
            }

            @Override
            public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) {
            }

            @Override
            public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
            }
        };
    }

    private record Question(String question, String expectedPath, List<String> expectedAnswers) {
    }

    private record Row(Question question, List<String> paths, String context, long millis, boolean noResults) {
    }

}
