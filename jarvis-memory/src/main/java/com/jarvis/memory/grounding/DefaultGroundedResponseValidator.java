package com.jarvis.memory.grounding;

import com.jarvis.common.prompt.GroundedResponseValidator;
import com.jarvis.common.prompt.GroundedValidationResult;
import com.jarvis.common.prompt.GroundingSourceType;
import com.jarvis.common.prompt.PromptContext;
import com.jarvis.common.prompt.ResponseMode;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic validator for grounded personal responses.
 */
@Service
public class DefaultGroundedResponseValidator implements GroundedResponseValidator {

    private static final Pattern HARDWARE_MODEL_PATTERN = Pattern.compile(
            "(?i)\\b(" +
                    "i[3579]-\\d{4,5}[a-z]*|" +
                    "ryzen\\s+[3579]\\s+\\d{4}[a-z0-9]*|" +
                    "rtx\\s*\\d{4}|gtx\\s*\\d{3,4}|rx\\s*\\d{3,4}|" +
                    "\\d{2,3}\\s*gb\\s+(ddr[345]?|ram)|" +
                    "ddr[345]|" +
                    "980\\s*pro|990\\s*pro|" +
                    "corsair|noctua|seasonic|be\\s*quiet" +
                    ")\\b"
    );

    private static final Set<String> MEMORY_CLAIMS = Set.of(
            "pamietam", "z mojej pamieci", "z pamieci", "z zapisanych danych",
            "i remember", "from memory", "from stored data"
    );

    private static final Set<String> KNOWLEDGE_CLAIMS = Set.of(
            "z lokalnej wiedzy", "z biblioteki wiedzy", "from local knowledge", "from the knowledge base"
    );

    @Override
    public GroundedValidationResult validate(String response, PromptContext promptContext) {
        PromptContext context = promptContext == null ? PromptContext.empty() : promptContext;
        if (context.responseMode() != ResponseMode.GROUNDED_PERSONAL) {
            return GroundedValidationResult.success();
        }
        String answer = normalize(response);
        String sourceText = normalize(context.groundingSources().stream()
                .map(source -> source.title() + " " + source.contentPreview())
                .collect(Collectors.joining(" ")));
        if (!context.hasMemory() && containsAny(answer, MEMORY_CLAIMS)) {
            return GroundedValidationResult.invalid("Response claimed memory evidence, but no memory source was supplied.");
        }
        if (!context.hasKnowledge() && containsAny(answer, KNOWLEDGE_CLAIMS)) {
            return GroundedValidationResult.invalid("Response claimed knowledge evidence, but no knowledge source was supplied.");
        }
        var matcher = HARDWARE_MODEL_PATTERN.matcher(response == null ? "" : response);
        while (matcher.find()) {
            String claim = normalize(matcher.group());
            if (!claim.isBlank() && !sourceText.contains(claim) && !compact(sourceText).contains(compact(claim))) {
                return GroundedValidationResult.invalid("Unsupported hardware model claim: " + matcher.group());
            }
        }
        if (!hasSupportingPersonalSource(context) && looksLikePersonalFact(answer)) {
            return GroundedValidationResult.invalid("Response presented personal facts without supporting sources.");
        }
        return GroundedValidationResult.success();
    }

    private boolean hasSupportingPersonalSource(PromptContext context) {
        return context.groundingSources().stream()
                .anyMatch(source -> source.type() == GroundingSourceType.MEMORY
                        || source.type() == GroundingSourceType.KNOWLEDGE
                        || source.type() == GroundingSourceType.CONVERSATION
                        || source.type() == GroundingSourceType.TOOL);
    }

    private boolean looksLikePersonalFact(String answer) {
        return (answer.contains("masz ") || answer.contains("posiadasz ") || answer.contains("uzywasz ")
                || answer.contains("you have ") || answer.contains("you own ") || answer.contains("you use "))
                && !answer.contains("nie mam zapisanej")
                && !answer.contains("i do not have");
    }

    private boolean containsAny(String value, Set<String> phrases) {
        return phrases.stream().anyMatch(value::contains);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[^a-z0-9]+", "");
    }
}
