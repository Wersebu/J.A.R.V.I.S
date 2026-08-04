package com.jarvis.api.controller;

import com.jarvis.memory.debug.PipelineDebugService;
import com.jarvis.memory.debug.PipelineDebugSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing diagnostic pipeline information.
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    private final PipelineDebugService pipelineDebugService;

    /**
     * Creates the debug controller.
     *
     * @param pipelineDebugService pipeline debug service
     */
    public DebugController(PipelineDebugService pipelineDebugService) {
        this.pipelineDebugService = pipelineDebugService;
    }

    /**
     * Returns the latest cognitive pipeline snapshot.
     *
     * @return latest snapshot
     */
    @GetMapping("/pipeline/latest")
    public PipelineDebugSnapshot latestPipeline() {
        return pipelineDebugService.latest();
    }
}
