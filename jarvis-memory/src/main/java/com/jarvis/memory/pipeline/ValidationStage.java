package com.jarvis.memory.pipeline;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Validates the incoming request.
 */
@Service
@Order(10)
public class ValidationStage implements PipelineStage {

    @Override
    public String name() {
        return "ValidationStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        String message = context.request().message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        return context;
    }
}
