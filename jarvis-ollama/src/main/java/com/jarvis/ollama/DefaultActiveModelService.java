package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.InstalledModel;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelSwitchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Single source of truth for the active Ollama model, validated against Ollama's live installed
 * model list and persisted across restarts. Runtime-switchable: {@link #switchTo(String)} only
 * changes which model future requests use, never a request already in flight, since callers read
 * {@link #activeModel()} once per request to build an immutable {@code Brain}.
 */
@Service
public class DefaultActiveModelService implements ActiveModelService, InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultActiveModelService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;
    private final Resource activeModelResource;
    private final AtomicReference<String> activeModel;

    /**
     * Creates the active model service.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama properties, providing the base URL and configured default model
     * @param activeModelResource file backing persisted active-model selection
     */
    public DefaultActiveModelService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties,
            @Value("${jarvis.model.active-model-file:file:config/active-model.txt}") Resource activeModelResource
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.activeModelResource = activeModelResource;
        this.activeModel = new AtomicReference<>(properties.model());
    }

    /**
     * Resolves the startup active model: persisted selection when still installed, falling back to
     * the configured default, then to the first installed model, without ever failing startup.
     */
    @Override
    public void afterPropertiesSet() {
        String persisted = readPersisted();
        try {
            List<InstalledModel> installed = fetchInstalledModels();
            String resolved = resolveStartupModel(persisted, installed);
            activeModel.set(resolved);
            LOGGER.info("[JARVIS] [MODEL] Active model resolved at startup: model={} persisted={} installedCount={}",
                    resolved, persisted, installed.size());
        } catch (RuntimeException exception) {
            String fallback = persisted != null && !persisted.isBlank() ? persisted : properties.model();
            activeModel.set(fallback);
            LOGGER.warn("[JARVIS] [MODEL] Ollama unreachable at startup, active model set without validation: "
                    + "model={} reason={}", fallback, exception.getMessage());
        }
    }

    private String resolveStartupModel(String persisted, List<InstalledModel> installed) {
        List<String> installedNames = installed.stream().map(InstalledModel::name).toList();
        if (persisted != null && !persisted.isBlank() && installedNames.contains(persisted)) {
            return persisted;
        }
        if (persisted != null && !persisted.isBlank()) {
            LOGGER.warn("[JARVIS] [MODEL] Persisted active model no longer installed, ignoring: model={}", persisted);
        }
        if (installedNames.contains(properties.model())) {
            return properties.model();
        }
        if (!installedNames.isEmpty()) {
            LOGGER.warn("[JARVIS] [MODEL] Configured default model not installed, falling back to first installed model: "
                    + "configured={} fallback={}", properties.model(), installedNames.get(0));
            return installedNames.get(0);
        }
        return properties.model();
    }

    private static final Map<String, ModelCapability> RAW_CAPABILITY_MAPPING = Map.of(
            "completion", ModelCapability.TEXT,
            "vision", ModelCapability.VISION,
            "tools", ModelCapability.TOOLS,
            "thinking", ModelCapability.THINKING
    );

    @Override
    public String activeModel() {
        return activeModel.get();
    }

    @Override
    public Set<ModelCapability> activeModelCapabilities() {
        try {
            return fetchInstalledModels().stream()
                    .filter(model -> model.name().equals(activeModel()))
                    .findFirst()
                    .map(InstalledModel::capabilities)
                    .orElse(Set.of());
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] [MODEL] Failed to resolve active model capabilities: {}", exception.getMessage());
            return Set.of();
        }
    }

    @Override
    public ModelCatalog catalog() {
        try {
            List<InstalledModel> installed = fetchInstalledModels();
            return new ModelCatalog(installed, activeModel(), true, null);
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] [MODEL] Failed to fetch installed models: {}", exception.getMessage());
            return new ModelCatalog(List.of(), activeModel(), false, exception.getMessage());
        }
    }

    @Override
    public synchronized ModelSwitchResult switchTo(String requestedModel) {
        String normalized = requestedModel == null ? "" : requestedModel.strip();
        if (normalized.isEmpty()) {
            return ModelSwitchResult.rejected(activeModel(), "Model name must not be blank");
        }
        List<InstalledModel> installed;
        try {
            installed = fetchInstalledModels();
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] [MODEL] Model switch rejected, provider unreachable: requested={} reason={}",
                    normalized, exception.getMessage());
            return ModelSwitchResult.rejected(activeModel(), "Ollama is unreachable: " + exception.getMessage());
        }
        boolean installedMatch = installed.stream().anyMatch(model -> model.name().equals(normalized));
        if (!installedMatch) {
            LOGGER.warn("[JARVIS] [MODEL] Model switch rejected, model not installed: requested={} installed={}",
                    normalized, installed.stream().map(InstalledModel::name).collect(Collectors.joining(",")));
            return ModelSwitchResult.rejected(activeModel(), "Model is not installed in Ollama: " + normalized);
        }
        String previous = activeModel.getAndSet(normalized);
        persist(normalized);
        LOGGER.info("[JARVIS] [MODEL] Active model switched: previous={} active={}", previous, normalized);
        return ModelSwitchResult.success(normalized);
    }

    private List<InstalledModel> fetchInstalledModels() {
        String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/tags";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OllamaException("Ollama /api/tags returned status " + response.statusCode());
            }
            OllamaTagsResponse tagsResponse = objectMapper.readValue(response.body(), OllamaTagsResponse.class);
            return tagsResponse.models().stream()
                    .map(model -> new InstalledModel(
                            model.name(),
                            model.details() == null ? "" : nullToEmpty(model.details().family()),
                            model.details() == null ? "" : nullToEmpty(model.details().parameterSize()),
                            model.size() == null ? 0L : model.size(),
                            mapCapabilities(model.capabilities())))
                    .toList();
        } catch (IOException exception) {
            throw new OllamaException("Failed to reach Ollama at " + endpoint, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Interrupted while reaching Ollama at " + endpoint, exception);
        }
    }

    private String readPersisted() {
        try {
            Path path = resourcePath();
            if (path == null || !Files.exists(path)) {
                return null;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? null : content;
        } catch (IOException exception) {
            LOGGER.warn("[JARVIS] [MODEL] Failed to read persisted active model: {}", exception.getMessage());
            return null;
        }
    }

    private void persist(String model) {
        try {
            Path path = resourcePath();
            if (path == null) {
                return;
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, model + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("[JARVIS] [MODEL] Failed to persist active model {}: {}", model, exception.getMessage());
        }
    }

    private Path resourcePath() {
        try {
            return activeModelResource.getFile().toPath().toAbsolutePath().normalize();
        } catch (IOException exception) {
            return null;
        }
    }

    private static Set<ModelCapability> mapCapabilities(List<String> rawCapabilities) {
        if (rawCapabilities == null || rawCapabilities.isEmpty()) {
            return Set.of();
        }
        Set<ModelCapability> mapped = EnumSet.noneOf(ModelCapability.class);
        for (String raw : rawCapabilities) {
            ModelCapability capability = RAW_CAPABILITY_MAPPING.get(raw == null ? "" : raw.toLowerCase(Locale.ROOT));
            if (capability != null) {
                mapped.add(capability);
            }
        }
        return mapped;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.toLowerCase(Locale.ROOT).endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
