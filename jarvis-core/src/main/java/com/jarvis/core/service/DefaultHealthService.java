package com.jarvis.core.service;

import com.jarvis.api.service.HealthService;
import com.jarvis.common.dto.HealthResponse;
import com.jarvis.common.model.ModelWarmupReadiness;
import com.jarvis.common.model.ModelWarmupStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Default health service for Jarvis version 0.1.
 */
@Service
public class DefaultHealthService implements HealthService {

    private final String version;
    private final ObjectProvider<ModelWarmupReadiness> modelWarmupReadiness;

    /**
     * Creates the default health service.
     *
     * @param version public Jarvis backend version
     */
    public DefaultHealthService(
            @Value("${jarvis.version}") String version,
            ObjectProvider<ModelWarmupReadiness> modelWarmupReadiness
    ) {
        this.version = version;
        this.modelWarmupReadiness = modelWarmupReadiness;
    }

    /**
     * Returns the online status for the backend.
     *
     * @return health response
     */
    @Override
    public HealthResponse health() {
        ModelWarmupStatus modelStatus = modelWarmupReadiness
                .getIfAvailable(() -> new NoopModelWarmupReadiness())
                .status();
        String status = switch (modelStatus) {
            case READY -> "online";
            case FAILED -> "degraded";
            case NOT_STARTED, WARMING -> "warming";
        };
        return new HealthResponse(status, version, modelStatus.name());
    }

    private static final class NoopModelWarmupReadiness implements ModelWarmupReadiness {

        @Override
        public ModelWarmupStatus status() {
            return ModelWarmupStatus.READY;
        }

        @Override
        public java.util.Map<String, com.jarvis.common.model.ModelStartupPolicy> policies() {
            return java.util.Map.of();
        }

        @Override
        public java.util.Map<String, ModelWarmupStatus> modelStatuses() {
            return java.util.Map.of();
        }
    }
}
