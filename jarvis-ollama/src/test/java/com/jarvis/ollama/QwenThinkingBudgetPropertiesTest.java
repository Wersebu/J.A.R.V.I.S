package com.jarvis.ollama;

import com.jarvis.common.model.QwenThinkingBudgetMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the Qwen thinking-budget target-model matching and mode->token
 * resolution: the feature must apply to exactly one configured model name (default
 * {@code qwen3.5:9b}), tolerant of case/whitespace but never generalized to other Qwen versions.
 */
class QwenThinkingBudgetPropertiesTest {

    @Test
    void matchesExactTargetCaseAndWhitespaceInsensitively() {
        QwenThinkingBudgetProperties properties = new QwenThinkingBudgetProperties();

        assertThat(properties.matchesTarget("qwen3.5:9b")).isTrue();
        assertThat(properties.matchesTarget("QWEN3.5:9B")).isTrue();
        assertThat(properties.matchesTarget("  qwen3.5:9b  ")).isTrue();
        assertThat(properties.matchesTarget("Qwen3.5:9b")).isTrue();
    }

    @Test
    void neverMatchesOtherQwenVersionsOrOtherModels() {
        QwenThinkingBudgetProperties properties = new QwenThinkingBudgetProperties();

        assertThat(properties.matchesTarget("qwen3.5:14b")).isFalse();
        assertThat(properties.matchesTarget("qwen3:8b")).isFalse();
        assertThat(properties.matchesTarget("qwen3.5:9b-instruct")).isFalse();
        assertThat(properties.matchesTarget("gpt-oss:20b")).isFalse();
        assertThat(properties.matchesTarget(null)).isFalse();
    }

    @Test
    void resolvesConfiguredTokenCapPerMode() {
        QwenThinkingBudgetProperties properties = new QwenThinkingBudgetProperties();

        properties.setMode(QwenThinkingBudgetMode.OFF);
        assertThat(properties.resolveMaxTokens()).isZero();

        properties.setMode(QwenThinkingBudgetMode.LOW);
        assertThat(properties.resolveMaxTokens()).isEqualTo(250);

        properties.setMode(QwenThinkingBudgetMode.NORMAL);
        assertThat(properties.resolveMaxTokens()).isEqualTo(500);

        properties.setMode(QwenThinkingBudgetMode.HIGH);
        assertThat(properties.resolveMaxTokens()).isEqualTo(1500);

        properties.setMode(QwenThinkingBudgetMode.MAX);
        assertThat(properties.resolveMaxTokens()).isEqualTo(-1);
    }

    @Test
    void defaultsToNormalModeWithFiveHundredTokens() {
        QwenThinkingBudgetProperties properties = new QwenThinkingBudgetProperties();

        assertThat(properties.getMode()).isEqualTo(QwenThinkingBudgetMode.NORMAL);
        assertThat(properties.resolveMaxTokens()).isEqualTo(500);
        assertThat(properties.getTargetModel()).isEqualTo("qwen3.5:9b");
    }

    @Test
    void tokenCapsPerModeAreIndividuallyReconfigurable() {
        QwenThinkingBudgetProperties properties = new QwenThinkingBudgetProperties();
        properties.setMode(QwenThinkingBudgetMode.NORMAL);
        properties.setNormalTokens(750);

        assertThat(properties.resolveMaxTokens()).isEqualTo(750);
    }
}
