package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic brain router based on isolated routing rules.
 */
@Service
public class RuleBasedBrainRouter implements BrainRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleBasedBrainRouter.class);

    private final BrainCatalog brainCatalog;
    private final List<BrainRoutingRule> rules;

    /**
     * Creates the rule-based brain router.
     *
     * @param brainCatalog configured brain catalog
     */
    public RuleBasedBrainRouter(BrainCatalog brainCatalog) {
        this.brainCatalog = brainCatalog;
        this.rules = List.of(
                new GreetingRoutingRule(),
                new SimpleQuestionRoutingRule(),
                new ReasoningRoutingRule()
        );
    }

    /**
     * Selects a brain using deterministic rules.
     *
     * @param request chat request
     * @return selected brain
     */
    @Override
    public Brain select(ChatRequest request) {
        Instant startedAt = Instant.now();
        String message = normalize(request.message());

        RoutingDecision decision = rules.stream()
                .filter(rule -> rule.matches(message))
                .findFirst()
                .map(rule -> new RoutingDecision(rule.brainType(), rule.reason()))
                .orElse(new RoutingDecision(BrainType.FAST, "Default"));

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        Brain brain = brainCatalog.get(decision.brainType())
                .withRoutingMetadata(decision.reason(), latencyMs);
        LOGGER.info(
                "[JARVIS] Selected Brain: {}, Selected Model: {}, Selection reason: {}, Router latency: {} ms",
                brain.type(),
                brain.model(),
                decision.reason(),
                latencyMs
        );
        return brain;
    }

    private String normalize(String message) {
        return message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
    }
}
