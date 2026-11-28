package com.jarvis.ollama.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.dto.moderation.ModerationRequest;
import com.jarvis.api.service.moderation.ModerationModelAvailability;
import com.jarvis.api.service.moderation.ModerationModelClient;
import com.jarvis.api.service.moderation.ModerationModelException;
import com.jarvis.api.service.moderation.ModerationModelResponse;
import com.jarvis.ollama.OllamaChatMessage;
import com.jarvis.ollama.OllamaChatRequest;
import com.jarvis.ollama.OllamaProperties;
import com.jarvis.ollama.OllamaTagsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama transport for the isolated TopkiMC moderation pipeline.
 */
@Service
public class OllamaModerationModelClient implements ModerationModelClient {
    private org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.InferenceControl> engines;
    private org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.AIProvider> providers;
    @org.springframework.beans.factory.annotation.Autowired
    public void routing(org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.InferenceControl> engines,org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.AIProvider> providers){this.engines=engines;this.providers=providers;}
    private com.jarvis.common.ai.InferenceControl.Selection selection(){var pinned=com.jarvis.common.ai.InferenceSelectionContext.current();var control=engines==null?null:engines.getIfAvailable();return pinned!=null?pinned:control==null?null:control.selection();}

    private com.jarvis.common.ai.ModelGateway gateway;
    private com.jarvis.ollama.ContextBudgetService budgets;
    @org.springframework.beans.factory.annotation.Autowired
    public void inferenceInfrastructure(com.jarvis.common.ai.ModelGateway gateway,com.jarvis.ollama.ContextBudgetService budgets){this.gateway=gateway;this.budgets=budgets;}
    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaModerationModelClient.class);
    private static final int MAX_RESPONSE_BYTES = 16_000;
    private static final String MODERATION_THINK = "low";
    private static final int MODERATION_NUM_PREDICT = 1_200;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties ollamaProperties;

    public OllamaModerationModelClient(HttpClient httpClient, ObjectMapper objectMapper, OllamaProperties ollamaProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.ollamaProperties = ollamaProperties;
    }

    private int metadataLimit(String model){
        var assigned=selection();
        try(var scope=budgets.open(new com.jarvis.common.ai.Brain(com.jarvis.common.ai.BrainType.CLASSIFIER,"ollama",model,"Moderation","",0,com.jarvis.common.ai.ReasoningLevel.LOW,assigned==null?"":assigned.configurationId()),com.jarvis.common.ai.AIJobType.BACKGROUND)){return budgets.currentPlan().modelMax();}
    }
    @Override
    public ModerationModelResponse moderate(ModerationRequest request, String systemPrompt, String model, Duration timeout) {
        var selected=selection();
        return com.jarvis.common.ai.InferenceSelectionContext.call(selected,()->{
            if(selected!=null&&!selected.engine().equals("ollama"))return moderateActive(request,systemPrompt,selected,timeout);
            return moderateOllama(request,systemPrompt,selected==null?model:selected.model(),timeout);
        });
    }
    private ModerationModelResponse moderateActive(ModerationRequest request,String systemPrompt,com.jarvis.common.ai.InferenceControl.Selection selected,Duration timeout){
        if(timeout.isZero()||timeout.isNegative())throw new ModerationModelException("Moderation timeout");
        long started=System.nanoTime();
        try(var settings=com.jarvis.common.ai.InferenceSettingsContext.open(new com.jarvis.common.ai.InferenceSettingsContext.Settings("moderation",50,true,started+timeout.toNanos()));var deadline=new com.jarvis.ollama.InferenceDeadline()){
            var provider=providers.orderedStream().filter(p->p.provider().equals(selected.engine())).findFirst().orElseThrow(()->new ModerationModelException("Active moderation provider unavailable"));
            var brain=new com.jarvis.common.ai.Brain(com.jarvis.common.ai.BrainType.CLASSIFIER,selected.engine(),selected.model(),"Moderation","Active inference configuration",0,com.jarvis.common.ai.ReasoningLevel.LOW,selected.configurationId());
            // Existing moderation parser validates the JSON business result and fails closed. No
            // structured-output or executable-tool capability is assumed for an active model.
            var result=provider.toolChat(brain,List.of(com.jarvis.common.ai.ModelMessage.system(systemPrompt),com.jarvis.common.ai.ModelMessage.user(userPayload(request))),List.of(),com.jarvis.common.ai.AIJobType.BACKGROUND);
            if(result.hasToolCalls()||result.content().isBlank()||List.of("length","max_tokens").contains(result.finishReason())||result.content().getBytes(StandardCharsets.UTF_8).length>MAX_RESPONSE_BYTES)throw new ModerationModelException("Invalid or incomplete moderation response");
            return new ModerationModelResponse(result.content(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),selected.model());
        }catch(IOException|com.jarvis.common.ai.AIProviderException failure){throw new ModerationModelException("Active moderation inference failed",failure);}
    }
    private ModerationModelResponse moderateOllama(ModerationRequest request,String systemPrompt,String model,Duration timeout){
        try {
            String endpoint = normalizeBaseUrl(ollamaProperties.baseUrl()) + "/api/chat";
            Map<String, Object> options = Map.of(
                    "temperature", 0,
                    "num_predict", MODERATION_NUM_PREDICT,
                    "num_ctx", budgets==null?16384:Math.min(budgets.physicalContext(),metadataLimit(model))
            );
            OllamaChatRequest requestBody = new OllamaChatRequest(
                    model,
                    List.of(
                            new OllamaChatMessage("system", systemPrompt, "", List.of(), ""),
                            new OllamaChatMessage("user", userPayload(request), "", List.of(), "")
                    ),
                    List.of(),
                    false,
                    MODERATION_THINK,
                    ollamaProperties.keepAlive(),
                    options,
                    responseSchema()
            );
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            long started = System.nanoTime();
            HttpResponse<byte[]> response;
            try(var lease=gateway==null?null:gateway.acquire(com.jarvis.common.ai.AIJobType.BACKGROUND,"moderation")){
                var assigned=com.jarvis.common.ai.InferenceSelectionContext.current();var control=engines==null?null:engines.getIfAvailable();
                if(assigned!=null&&control!=null&&!assigned.configurationId().equals(control.selection().configurationId()))throw new ModerationModelException("STALE_CONFIGURATION: moderation selection changed");
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            }
            long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new ModerationModelException("Moderation model response too large");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModerationModelException("Moderation model HTTP status " + response.statusCode());
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(response.body());
            } catch (JsonProcessingException exception) {
                LOGGER.info("[TOPKIMC_MODERATION] parseStage=ollama_envelope_json reason={} responseLength={}",
                        exception.getClass().getSimpleName(), response.body().length);
                throw new ModerationModelException("Moderation model envelope JSON is malformed", exception);
            }
            JsonNode contentNode = root.path("message").path("content");
            String content = extractContent(contentNode);
            return new ModerationModelResponse(content, latencyMs, model);
        } catch (IOException exception) {
            throw new ModerationModelException("Moderation model is unreachable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModerationModelException("Moderation model call was interrupted", exception);
        }
    }

    private String extractContent(JsonNode contentNode) throws IOException {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isObject() || contentNode.isArray()) {
            LOGGER.info("[TOPKIMC_MODERATION] parseStage=ollama_message_content contentShape={} responseContentLength={}",
                    contentNode.getNodeType(), contentNode.toString().length());
            return objectMapper.writeValueAsString(contentNode);
        }
        return contentNode.asText("");
    }

    @Override
    public ModerationModelAvailability availability(String model, Duration timeout) {
        var selected=selection();
        if(selected!=null&&!selected.engine().equals("ollama")){
            var control=engines.getIfAvailable();boolean ready=control!=null&&"READY".equals(control.state().get("status"));
            return new ModerationModelAvailability(ready,ready);
        }
        if(selected!=null)model=selected.model();
        if (model == null || model.isBlank()) {
            return new ModerationModelAvailability(false, false);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(ollamaProperties.baseUrl()) + "/api/tags"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModerationModelAvailability(false, false);
            }
            OllamaTagsResponse tags = objectMapper.readValue(response.body(), OllamaTagsResponse.class);
            String activeModel=model.strip();boolean installed = tags.models() != null
                    && tags.models().stream().anyMatch(tag -> tag.name().equalsIgnoreCase(activeModel));
            return new ModerationModelAvailability(true, installed);
        } catch (IOException exception) {
            return new ModerationModelAvailability(false, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ModerationModelAvailability(false, false);
        }
    }

    private String userPayload(ModerationRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "TOPKIMC_SERVER_PROFILE_MODERATION_PAYLOAD");
        payload.put("trustedPolicyVersion", request.policyVersion());
        payload.put("data", request);
        return objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of(
                "decision", "risk", "categories", "reasonCode", "summary",
                "adminReviewRequired", "modelVersion", "policyVersion"
        ));
        schema.put("properties", Map.of(
                "decision", Map.of("type", "string", "enum", List.of("CLEAN", "FLAGGED", "ERROR")),
                "risk", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                "categories", Map.of(
                        "type", "array",
                        "description", "Array of category enum strings only, for example [\"SCAM\"]. Do not return objects.",
                        "maxItems", 10,
                        "items", Map.of("type", "string", "enum", List.of(
                                "PHISHING", "MALWARE", "ACCOUNT_THEFT_LINKS", "STOLEN_ACCOUNT_SALES",
                                "CHEATS_UNAUTHORIZED_SOFTWARE", "SCAM", "SEXUAL_CONTENT", "VIOLENCE",
                                "HATE_SPEECH", "HARASSMENT", "IMPERSONATION", "MISLEADING_INFO", "SPAM",
                                "SEO_KEYWORD_STUFFING", "UNRELATED_ADVERTISING", "COPYRIGHT",
                                "PROMPT_INJECTION_ATTEMPT"
                        ))
                ),
                "reasonCode", Map.of("type", "string", "minLength", 1, "maxLength", 64),
                "summary", Map.of("type", "string", "maxLength", 500),
                "adminReviewRequired", Map.of("type", "boolean"),
                "modelVersion", Map.of("type", "string", "maxLength", 64),
                "policyVersion", Map.of("type", "string", "maxLength", 32)
        ));
        return schema;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
