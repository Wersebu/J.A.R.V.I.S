package com.jarvis.api.controller;

import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Debug endpoints for recent request diagnostics.
 */
@RestController
@RequestMapping(path = "/api/v1/debug/requests", produces = MediaType.APPLICATION_JSON_VALUE)
public class DebugRequestController {

    private final InferenceDiagnosticsService diagnosticsService;

    /**
     * Creates the debug controller.
     *
     * @param diagnosticsService diagnostics service
     */
    public DebugRequestController(InferenceDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * Returns the latest request diagnostics.
     *
     * @return diagnostics
     */
    @GetMapping("/latest")
    public InferenceDiagnostics latest() {
        return diagnosticsService.latest()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No request diagnostics available"));
    }

    /**
     * Returns request diagnostics by id.
     *
     * @param requestId request id
     * @return diagnostics
     */
    @GetMapping("/{requestId}")
    public InferenceDiagnostics find(@PathVariable UUID requestId) {
        return diagnosticsService.find(requestId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Request diagnostics not found"));
    }
}
