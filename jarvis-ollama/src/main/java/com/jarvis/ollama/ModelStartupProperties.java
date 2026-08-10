package com.jarvis.ollama;

import com.jarvis.common.model.ModelStartupPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-independent startup policy configuration for local AI models.
 */
@ConfigurationProperties(prefix = "jarvis.model-startup")
public class ModelStartupProperties {

    private Map<String, ModelPolicyProperties> models = defaultModels();

    /**
     * Returns configured model policies.
     *
     * @return model policy map
     */
    public Map<String, ModelPolicyProperties> getModels() {
        Map<String, ModelPolicyProperties> merged = defaultModels();
        models.forEach((model, policy) -> merged.put(normalizeModelName(model), policy));
        return merged;
    }

    /**
     * Sets configured model policies.
     *
     * @param models model policy map
     */
    public void setModels(Map<String, ModelPolicyProperties> models) {
        if (models == null) {
            this.models = defaultModels();
            return;
        }
        Map<String, ModelPolicyProperties> normalized = new LinkedHashMap<>();
        models.forEach((model, policy) -> normalized.put(normalizeModelName(model), policy));
        this.models = normalized;
    }

    private static String normalizeModelName(String model) {
        if ("gpt-oss20b".equals(model)) {
            return "gpt-oss:20b";
        }
        return model;
    }

    private static Map<String, ModelPolicyProperties> defaultModels() {
        Map<String, ModelPolicyProperties> defaults = new LinkedHashMap<>();
        defaults.put("gpt-oss:20b", new ModelPolicyProperties(ModelStartupPolicy.EAGER, "-1m"));
        defaults.put("Chatterbox", new ModelPolicyProperties(ModelStartupPolicy.LAZY, ""));
        defaults.put("Whisper", new ModelPolicyProperties(ModelStartupPolicy.LAZY, ""));
        defaults.put("Vision", new ModelPolicyProperties(ModelStartupPolicy.LAZY, ""));
        return defaults;
    }

    /**
     * Startup policy for one model.
     */
    public static class ModelPolicyProperties {

        private ModelStartupPolicy startupPolicy = ModelStartupPolicy.LAZY;
        private String keepAlive = "";

        /**
         * Creates an empty policy for configuration binding.
         */
        public ModelPolicyProperties() {
        }

        /**
         * Creates a policy.
         *
         * @param startupPolicy startup policy
         * @param keepAlive provider keep-alive value
         */
        public ModelPolicyProperties(ModelStartupPolicy startupPolicy, String keepAlive) {
            this.startupPolicy = startupPolicy == null ? ModelStartupPolicy.LAZY : startupPolicy;
            this.keepAlive = keepAlive == null ? "" : keepAlive;
        }

        /**
         * Returns startup policy.
         *
         * @return startup policy
         */
        public ModelStartupPolicy getStartupPolicy() {
            return startupPolicy;
        }

        /**
         * Sets startup policy.
         *
         * @param startupPolicy startup policy
         */
        public void setStartupPolicy(ModelStartupPolicy startupPolicy) {
            this.startupPolicy = startupPolicy == null ? ModelStartupPolicy.LAZY : startupPolicy;
        }

        /**
         * Returns provider keep-alive value.
         *
         * @return keep-alive value
         */
        public String getKeepAlive() {
            return keepAlive;
        }

        /**
         * Sets provider keep-alive value.
         *
         * @param keepAlive keep-alive value
         */
        public void setKeepAlive(String keepAlive) {
            this.keepAlive = keepAlive == null ? "" : keepAlive;
        }
    }
}
