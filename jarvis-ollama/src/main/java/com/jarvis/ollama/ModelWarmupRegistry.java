package com.jarvis.ollama;

import com.jarvis.common.model.ModelStartupPolicy;
import com.jarvis.common.model.ModelWarmupReadiness;
import com.jarvis.common.model.ModelWarmupStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores startup warmup state for provider-backed models.
 */
@Service
public class ModelWarmupRegistry implements ModelWarmupReadiness {

    private final ModelStartupProperties properties;
    private final AtomicReference<ModelWarmupStatus> status;
    private final Map<String, ModelWarmupStatus> modelStatuses;
    private final Set<String> unexpectedColdStartLogged;

    /**
     * Creates the registry.
     *
     * @param properties model startup properties
     */
    public ModelWarmupRegistry(ModelStartupProperties properties) {
        this.properties = properties;
        this.status = new AtomicReference<>(ModelWarmupStatus.NOT_STARTED);
        this.modelStatuses = new ConcurrentHashMap<>();
        this.unexpectedColdStartLogged = ConcurrentHashMap.newKeySet();
        properties.getModels().forEach((model, policy) -> modelStatuses.put(model, ModelWarmupStatus.NOT_STARTED));
    }

    @Override
    public ModelWarmupStatus status() {
        return status.get();
    }

    @Override
    public Map<String, ModelStartupPolicy> policies() {
        Map<String, ModelStartupPolicy> policies = new LinkedHashMap<>();
        properties.getModels().forEach((model, policy) -> policies.put(model, policy.getStartupPolicy()));
        return Map.copyOf(policies);
    }

    @Override
    public Map<String, ModelWarmupStatus> modelStatuses() {
        return Map.copyOf(modelStatuses);
    }

    /**
     * Marks global warmup as running.
     */
    public void warming() {
        status.set(ModelWarmupStatus.WARMING);
    }

    /**
     * Marks a model warmup status.
     *
     * @param model model name
     * @param modelStatus model status
     */
    public void markModel(String model, ModelWarmupStatus modelStatus) {
        modelStatuses.put(model, modelStatus);
    }

    /**
     * Marks global warmup as ready.
     */
    public void markReady() {
        status.set(ModelWarmupStatus.READY);
    }

    /**
     * Marks global warmup as failed.
     */
    public void failed() {
        status.set(ModelWarmupStatus.FAILED);
    }

    /**
     * Returns true when a model completed eager warmup.
     *
     * @param model model name
     * @return warm flag
     */
    public boolean eagerModelReady(String model) {
        return status() == ModelWarmupStatus.READY
                && policies().get(model) == ModelStartupPolicy.EAGER
                && modelStatuses.get(model) == ModelWarmupStatus.READY;
    }

    /**
     * Returns true once for an unexpected cold start for the model.
     *
     * @param model model name
     * @return whether this cold start should be logged
     */
    public boolean markUnexpectedColdStartIfFirst(String model) {
        return unexpectedColdStartLogged.add(model);
    }
}
