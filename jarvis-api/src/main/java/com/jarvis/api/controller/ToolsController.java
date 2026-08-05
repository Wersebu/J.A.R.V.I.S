package com.jarvis.api.controller;

import com.jarvis.tools.runtime.ToolRuntimeDebugService;
import com.jarvis.tools.runtime.ToolRuntimeSnapshot;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Diagnostics API for native J.A.R.V.I.S. tools.
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolsController {

    private final ToolRegistry toolRegistry;
    private final ToolRuntimeDebugService debugService;

    /**
     * Creates the tools controller.
     */
    public ToolsController(ToolRegistry toolRegistry, ToolRuntimeDebugService debugService) {
        this.toolRegistry = toolRegistry;
        this.debugService = debugService;
    }

    /**
     * Lists registered model-facing tool definitions.
     *
     * @return tool definitions
     */
    @GetMapping
    public List<ToolDefinition> tools() {
        return toolRegistry.definitions();
    }

    /**
     * Returns a recent native tool runtime snapshot.
     *
     * @param requestId request identifier
     * @return debug snapshot or 404
     */
    @GetMapping("/requests/{requestId}")
    public ResponseEntity<ToolRuntimeSnapshot> request(@PathVariable String requestId) {
        return debugService.find(requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
