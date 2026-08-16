package com.jarvis.ollama;

import com.jarvis.common.model.QwenThinkingBudgetMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of truth for the Qwen thinking-budget feature: which mode is active, the token
 * cap for each configurable mode, and which exact model name it applies to.
 *
 * <p>This intentionally applies to one exact, configured model name only (default
 * {@code qwen3.5:9b}) - never a family prefix - so other Qwen models and every other model are
 * completely unaffected. Matching is case/whitespace-insensitive since that is how the rest of
 * the codebase compares model names (see {@code DefaultActiveModelService}).
 */
@ConfigurationProperties(prefix = "jarvis.qwen-thinking-budget")
public class QwenThinkingBudgetProperties {

    private QwenThinkingBudgetMode mode = QwenThinkingBudgetMode.NORMAL;
    private String targetModel = "qwen3.5:9b";
    private int lowTokens = 250;
    private int normalTokens = 500;
    private int highTokens = 1500;

    public QwenThinkingBudgetMode getMode() {
        return mode;
    }

    public void setMode(QwenThinkingBudgetMode mode) {
        this.mode = mode == null ? QwenThinkingBudgetMode.NORMAL : mode;
    }

    public String getTargetModel() {
        return targetModel;
    }

    public void setTargetModel(String targetModel) {
        this.targetModel = targetModel == null || targetModel.isBlank() ? "qwen3.5:9b" : targetModel;
    }

    public int getLowTokens() {
        return lowTokens;
    }

    public void setLowTokens(int lowTokens) {
        this.lowTokens = lowTokens;
    }

    public int getNormalTokens() {
        return normalTokens;
    }

    public void setNormalTokens(int normalTokens) {
        this.normalTokens = normalTokens;
    }

    public int getHighTokens() {
        return highTokens;
    }

    public void setHighTokens(int highTokens) {
        this.highTokens = highTokens;
    }

    /**
     * Returns whether the given model is exactly the configured thinking-budget target -
     * never true for other models, including other Qwen versions.
     *
     * @param model model name to check
     * @return true when this is the exact configured target model
     */
    public boolean matchesTarget(String model) {
        return model != null && targetModel.equalsIgnoreCase(model.trim());
    }

    /**
     * Resolves the effective thinking-token cap for the currently configured mode.
     *
     * @return max thinking tokens, or -1 when the mode is {@link QwenThinkingBudgetMode#MAX} (unlimited)
     */
    public int resolveMaxTokens() {
        return switch (mode) {
            case OFF -> 0;
            case LOW -> lowTokens;
            case NORMAL -> normalTokens;
            case HIGH -> highTokens;
            case MAX -> -1;
        };
    }
}
