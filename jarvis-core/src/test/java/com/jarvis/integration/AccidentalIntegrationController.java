package com.jarvis.integration;

/** Unprofiled poison fixture: production scan must reject this entire namespace. */
@org.springframework.web.bind.annotation.RestController
public class AccidentalIntegrationController {
    @org.springframework.web.bind.annotation.GetMapping("/api/health")
    public String health() { return "THIS_MUST_NEVER_BE_REGISTERED"; }
}
