package com.jarvis.ollama;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Metadata only: no generation or gateway lease, and never infer limits from a model name. */
@Service
public class ModelMetadataResolver {
    public record Metadata(String model, String digest, int maxContext, long fetchedAt, java.util.Set<String> capabilities) {
        public Metadata(String model,String digest,int maxContext,long fetchedAt){this(model,digest,maxContext,fetchedAt,java.util.Set.of());}
    }
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String base;
    private final ConcurrentHashMap<String, Metadata> cache = new ConcurrentHashMap<>();
    public ModelMetadataResolver(HttpClient http, ObjectMapper mapper, OllamaProperties properties) {
        this.http = http; this.mapper = mapper; this.base = properties.baseUrl().replaceAll("/+$", "");
    }
    public Metadata resolve(String model) {
        Metadata found = cache.get(model);
        if (found != null && System.currentTimeMillis() - found.fetchedAt() < 300000) return found;
        // A per-model compute serializes metadata refresh only, never inference.
        return cache.compute(model, (key, old) -> {
            if (old != null && System.currentTimeMillis() - old.fetchedAt() < 300000) return old;
            try {
                var show = request("/api/show", mapper.writeValueAsString(java.util.Map.of("model", model)));
                var info = show.path("model_info");
                String architecture = info.path("general.architecture").asText();
                JsonNode limit = info.path(architecture + ".context_length");
                if (architecture.isBlank() || !limit.isIntegralNumber() || !limit.canConvertToInt() || limit.asInt() < 512)
                    throw new OllamaException("MODEL_LIMIT_UNKNOWN: metadata has no valid architecture context length");
                String digest = "";
                for (var tag : request("/api/tags", null).path("models")) {
                    if (model.equals(tag.path("name").asText()) || model.equals(tag.path("model").asText()) ||
                        (model + ":latest").equals(tag.path("name").asText())) digest = tag.path("digest").asText();
                }
                if (digest.isBlank()) throw new OllamaException("MODEL_LIMIT_UNKNOWN: model digest unavailable");
                var capabilities=new java.util.HashSet<String>();show.path("capabilities").forEach(n->capabilities.add(n.asText()));
                return new Metadata(model, digest, limit.asInt(), System.currentTimeMillis(),java.util.Set.copyOf(capabilities));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OllamaException("Model metadata interrupted", e); }
            catch (java.io.IOException e) { throw new OllamaException("MODEL_LIMIT_UNKNOWN: metadata unavailable", e); }
        });
    }
    private JsonNode request(String path, String body) throws java.io.IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (result.statusCode() != 200) throw new OllamaException("MODEL_LIMIT_UNKNOWN: HTTP " + result.statusCode());
        return mapper.readTree(result.body());
    }
    public void invalidate() { cache.clear(); }
}
