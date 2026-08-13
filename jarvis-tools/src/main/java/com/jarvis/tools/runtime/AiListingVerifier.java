package com.jarvis.tools.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model-driven, product-agnostic verification of whether one read marketplace listing page is
 * genuinely for the requested search target and variant.
 *
 * <p>This replaces regex/keyword entity matching as the sole gate: keyword overlap cannot tell
 * "RTX 4060 Ti" from "RTX 3060 Ti" reliably across arbitrary product categories, and a search
 * target built from stale request text (fixed before the model even knew the exact product)
 * silently accepts everything. The verifier is always given the model's own current, accurate
 * search target and decides semantically — general across any product category, not just GPUs.
 */
public class AiListingVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiListingVerifier.class);
    private static final int MAX_CONTENT_CHARS = 2500;
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObjectMapper objectMapper;

    /**
     * Creates the verifier.
     *
     * @param objectMapper JSON mapper
     */
    public AiListingVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies a listing against a search target using the given provider/brain.
     *
     * @param provider AI provider
     * @param brain selected brain
     * @param searchTarget the model's current, accurate description of the wanted product
     * @param title listing page title
     * @param content listing page content excerpt
     * @return structured verification decision
     */
    public ListingVerificationResult verify(AIProvider provider, Brain brain, String searchTarget, String title, String content) {
        String target = searchTarget == null ? "" : searchTarget.strip();
        if (target.isBlank()) {
            return new ListingVerificationResult(true, 0.4d, "No concrete search target available; accepting without strict verification.",
                    "", "", List.of());
        }
        String prompt = prompt(target, title, content);
        try {
            String raw = provider.chat(brain, prompt, AIJobType.BACKGROUND).response();
            return parse(raw);
        } catch (RuntimeException exception) {
            LOGGER.warn("[LISTING_VERIFICATION] verifier call failed target=\"{}\" error={}", target, exception.getMessage());
            return ListingVerificationResult.reject("Listing verification failed: " + safe(exception.getMessage()));
        }
    }

    private String prompt(String target, String title, String content) {
        return """
                You are a strict marketplace listing verifier.
                Decide whether this ONE specific listing genuinely matches the SEARCH TARGET the user wants —
                not just a similar or related product.

                SEARCH TARGET:
                %s

                LISTING TITLE:
                %s

                LISTING PAGE CONTENT (excerpt):
                %s

                Rules:
                - REJECT if the listing's product model, number, or capacity/size differs from the SEARCH TARGET,
                  even if the general product category matches (e.g. a different GPU model or VRAM size, a
                  different storage capacity, a different generation or trim).
                - Do NOT reject only because a manufacturer/brand of the specific unit (e.g. MSI, Gigabyte, Asus,
                  Zotac) is not explicitly named in the SEARCH TARGET — brand variation is allowed unless the
                  user explicitly required a specific brand.
                - REJECT if the page is not actually a for-sale listing of the product (e.g. a review, a category
                  page, an unrelated article, an accessory instead of the product itself).
                - Base the decision only on what the LISTING TITLE and LISTING PAGE CONTENT actually state. Never
                  assume or invent details that are not present.

                Return JSON only, no prose, no markdown fences:
                {"decision":"ACCEPT","confidence":0.0,"reason":"...","matchedProduct":"...","matchedVariant":"...","evidence":["..."]}
                """.formatted(target, safe(title), truncate(safe(content), MAX_CONTENT_CHARS));
    }

    private ListingVerificationResult parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            String decision = text(node, "decision");
            boolean accepted = "ACCEPT".equalsIgnoreCase(decision);
            double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble() : (accepted ? 0.6d : 0.0d);
            String reason = text(node, "reason");
            String matchedProduct = text(node, "matchedProduct");
            String matchedVariant = text(node, "matchedVariant");
            List<String> evidence = new ArrayList<>();
            if (node.path("evidence").isArray()) {
                node.path("evidence").forEach(item -> evidence.add(item.asText("")));
            }
            return new ListingVerificationResult(accepted, confidence, reason, matchedProduct, matchedVariant, evidence);
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.warn("[LISTING_VERIFICATION] could not parse verifier response: {}", abbreviate(raw));
            return ListingVerificationResult.reject("Verifier returned an unparseable response.");
        }
    }

    private String extractJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        Matcher matcher = JSON_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String abbreviate(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
