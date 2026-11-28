package com.jarvis.api.controller;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.tools.integration.IntegrationRegistry;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {
 private final IntegrationRegistry registry;
 public IntegrationController(IntegrationRegistry registry){this.registry=registry;}
 @GetMapping public Object state(){return registry.snapshot(CurrentUserContext.requireAuthenticatedUserId());}
}
