package com.jarvis.api.controller;

import com.jarvis.api.dto.ActiveModelRequest;
import com.jarvis.api.dto.ActiveModelResponse;
import com.jarvis.api.dto.ModelInfoResponse;
import com.jarvis.api.dto.ModelsResponse;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelSwitchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for listing installed models and switching the active model at runtime.
 */
@RestController
@RequestMapping("/api")
public class ModelController {

    private final ActiveModelService activeModelService;

    /**
     * Creates the model controller.
     *
     * @param activeModelService single source of truth for the active model
     */
    public ModelController(ActiveModelService activeModelService) {
        this.activeModelService = activeModelService;
    }

    /**
     * Lists installed models and the currently active one.
     *
     * @return model catalog
     */
    @GetMapping("/models")
    public ModelsResponse models() {
        ModelCatalog catalog = activeModelService.catalog();
        return new ModelsResponse(
                catalog.models().stream()
                        .map(model -> new ModelInfoResponse(model.name(), model.family(), model.parameterSize(), model.sizeBytes()))
                        .toList(),
                catalog.activeModel(),
                catalog.providerReachable(),
                catalog.error()
        );
    }

    /**
     * Switches the active model, validated against the provider's installed models.
     *
     * @param request requested model
     * @return 200 with the new active model on success, 400 with a clear error otherwise
     */
    @PostMapping("/models/active")
    public ResponseEntity<ActiveModelResponse> setActiveModel(@RequestBody ActiveModelRequest request) {
        String requestedModel = request == null ? null : request.model();
        ModelSwitchResult result = activeModelService.switchTo(requestedModel);
        ActiveModelResponse body = new ActiveModelResponse(result.success(), result.activeModel(), result.error());
        return result.success() ? ResponseEntity.ok(body) : ResponseEntity.badRequest().body(body);
    }
}
