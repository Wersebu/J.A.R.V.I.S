package com.jarvis.tools.integration;
import com.jarvis.tools.*;
/** Operation-owned permission checks; implementations may wait for a correlated consent, never authorize a write. */
public interface IntegrationAuthorizationPolicy {
 boolean supports(ToolRequest request);
 void prepare(ToolRequest request);
 default boolean recoverRead(ToolRequest request,ToolResult failure){return false;}
}
